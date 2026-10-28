// SPDX-License-Identifier: GPL-3.0-or-later
#include "mlv_gpu.h"

#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>

extern "C" {
#include "../matrix/matrix.h"  // MLV-App src/matrix (mlvprocessing include dir is src/processing)
}

#include "mlv_gamuts.inc"

namespace mlvgpu {

namespace {

// MLV-App's agx_compressed_matrix (raw_processing.c).
const double kAgx[9] = {0.84247906, 0.0784336, 0.07922375, 0.04232824, 0.87846864, 0.07916613,
                        0.04237565, 0.0784336, 0.87914297};

bool on(double v) { return v <= -0.01 || v >= 0.01; }

// Must match the Params block in mlv_grade.comp (std430).
struct Params {
    int32_t width, height, flags, highestGreen;
    float vignetteStrength, toningDry, pad0, pad1;
    float toningWet[4];
    float wb[12], agx[12], agxInv[12];
    float rgbToY[4];
    int32_t oPm0, oPm4, oPm8, oGamma, oOutR, oOutG, oOutB, pad2, pad3, pad4;
    int32_t oSat, oVib, oHueHue, oHueSat, oHueLuma, oLumaSat, oContrast, oVignette;
};

enum : int32_t {
    F_CREATIVE = 1, F_CONTRAST = 2, F_CAM = 4, F_EXR = 8, F_AGX = 16, F_HIGHLIGHTS = 32,
    F_HUEVS = 64, F_VIB = 128, F_VIB_POS = 256, F_SAT = 512, F_TONING = 1024, F_VIGNETTE = 2048,
};

struct Buf {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize size = 0;
    void* map = nullptr;
    bool coherent = true;
};

}  // namespace

bool supports(const processingObject_t* p) {
    if (*p->dual_iso != 0 || p->lut_on || p->filter_on || p->grainStrength > 0) return false;
    if (p->allow_creative_adjustments &&
        (on(p->clarity) || on(p->shadows_highlights.shadows) || on(p->shadows_highlights.highlights)))
        return false;
    if (p->gradient_enable && (on(p->gradient_exposure_stops) || on(p->gradient_contrast))) return false;
    // Spatial stages after the per-pixel loop (applyProcessingObject).
    if (p->denoiserStrength > 0 || p->rbfDenoiserLuma > 0 || p->rbfDenoiserChroma > 0 || p->ca_desaturate > 0) return false;
    if (p->sharpen > 0.005) return false;
    if (p->cs_zone.use_cs) return false;
    return true;
}

struct Engine::Impl {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t family = 0;
    VkPhysicalDeviceMemoryProperties memory{};
    VkDescriptorSetLayout gradeLayout = VK_NULL_HANDLE, outputLayout = VK_NULL_HANDLE;
    VkPipelineLayout gradePipeLayout = VK_NULL_HANDLE, outputPipeLayout = VK_NULL_HANDLE;
    VkPipeline gradePipe = VK_NULL_HANDLE, outputPipe = VK_NULL_HANDLE;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    VkDescriptorSet gradeSet = VK_NULL_HANDLE, outputSet = VK_NULL_HANDLE;
    VkCommandPool cmdPool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkQueryPool timer = VK_NULL_HANDLE;
    float tickNs = 1.0f;
    bool foreign = false;  // VK_EXT_queue_family_foreign enabled
    bool timestamps = false;  // the compute queue has timestampValidBits
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProps = nullptr;
    Buf tables, params, processed, luma, chroma, rgba;
    AHardwareBuffer* srcAhb = nullptr;  // imported source, cached
    VkImage srcImage = VK_NULL_HANDLE;
    VkDeviceMemory srcMemory = VK_NULL_HANDLE;
    VkImageView srcView = VK_NULL_HANDLE;
    int lastW = 0, lastH = 0;

    int32_t memoryType(uint32_t bits, VkMemoryPropertyFlags want) const {
        for (uint32_t i = 0; i < memory.memoryTypeCount; ++i)
            if ((bits & (1u << i)) && (memory.memoryTypes[i].propertyFlags & want) == want) return static_cast<int32_t>(i);
        return -1;
    }

    // Buffers the CPU reads back: cached memory (uncached reads of ~12 MB per
    // UHD frame cost ~150 ms), invalidated after each run when not coherent.
    bool ensureReadback(Buf& b, VkDeviceSize size) {
        if (b.buffer && b.size >= size) return true;
        release(b);
        VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bi.size = size;
        bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (vkCreateBuffer(device, &bi, nullptr, &b.buffer) != VK_SUCCESS) return false;
        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(device, b.buffer, &req);
        int32_t type = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
        if (type < 0) type = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (type < 0) { vkDestroyBuffer(device, b.buffer, nullptr); b = Buf{}; return false; }
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ai.allocationSize = req.size;
        ai.memoryTypeIndex = static_cast<uint32_t>(type);
        if (vkAllocateMemory(device, &ai, nullptr, &b.memory) != VK_SUCCESS) { vkDestroyBuffer(device, b.buffer, nullptr); b = Buf{}; return false; }
        vkBindBufferMemory(device, b.buffer, b.memory, 0);
        b.size = size;
        b.coherent = memory.memoryTypes[type].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        vkMapMemory(device, b.memory, 0, VK_WHOLE_SIZE, 0, &b.map);
        return true;
    }
    void invalidate(const Buf& b) {
        if (b.coherent || !b.map) return;
        VkMappedMemoryRange r{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE, nullptr, b.memory, 0, VK_WHOLE_SIZE};
        vkInvalidateMappedMemoryRanges(device, 1, &r);
    }

    bool ensure(Buf& b, VkDeviceSize size, bool host) {
        if (b.buffer && b.size >= size) return true;
        release(b);
        VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bi.size = size;
        bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
                   VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (vkCreateBuffer(device, &bi, nullptr, &b.buffer) != VK_SUCCESS) return false;
        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(device, b.buffer, &req);
        int32_t type = host ? memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                            : memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (type < 0) return false;
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ai.allocationSize = req.size;
        ai.memoryTypeIndex = static_cast<uint32_t>(type);
        if (vkAllocateMemory(device, &ai, nullptr, &b.memory) != VK_SUCCESS) { vkDestroyBuffer(device, b.buffer, nullptr); b = Buf{}; return false; }
        vkBindBufferMemory(device, b.buffer, b.memory, 0);
        b.size = size;
        if (host) vkMapMemory(device, b.memory, 0, VK_WHOLE_SIZE, 0, &b.map);
        return true;
    }
    void release(Buf& b) {
        if (b.map) vkUnmapMemory(device, b.memory);
        if (b.buffer) vkDestroyBuffer(device, b.buffer, nullptr);
        if (b.memory) vkFreeMemory(device, b.memory, nullptr);
        b = Buf{};
    }
    void releaseSource() {
        if (srcView) vkDestroyImageView(device, srcView, nullptr);
        if (srcImage) vkDestroyImage(device, srcImage, nullptr);
        if (srcMemory) vkFreeMemory(device, srcMemory, nullptr);
        if (srcAhb) AHardwareBuffer_release(srcAhb);
        srcView = VK_NULL_HANDLE; srcImage = VK_NULL_HANDLE; srcMemory = VK_NULL_HANDLE; srcAhb = nullptr;
    }

    // The RCD output (RGBA16F AHardwareBuffer) as a storage image, without copying.
    std::string importSource(AHardwareBuffer* ahb, int w, int h) {
        if (ahb == srcAhb) return "";
        releaseSource();
        VkAndroidHardwareBufferFormatPropertiesANDROID fmt{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
        VkAndroidHardwareBufferPropertiesANDROID props{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
        props.pNext = &fmt;
        if (getAhbProps(device, ahb, &props) != VK_SUCCESS) return "AHardwareBuffer properties";
        // As RawLens's importAhb: the driver's format for this buffer (an
        // opaque external format cannot be a storage image -> CPU path).
        const VkFormat format = fmt.format;
        if (format == VK_FORMAT_UNDEFINED) return "RCD output has an external-only format";
        VkExternalMemoryImageCreateInfo ext{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        ii.pNext = &ext;
        ii.imageType = VK_IMAGE_TYPE_2D;
        ii.format = format;
        ii.extent = {static_cast<uint32_t>(w), static_cast<uint32_t>(h), 1};
        ii.mipLevels = 1; ii.arrayLayers = 1;
        ii.samples = VK_SAMPLE_COUNT_1_BIT;
        ii.usage = VK_IMAGE_USAGE_STORAGE_BIT;
        ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        // The app allocates this buffer for GPU use, so OPTIMAL first (as RawLens
        // for its own outputs), LINEAR if the driver refuses; each only where the
        // format supports storage images with that tiling.
        VkFormatProperties fp{};
        vkGetPhysicalDeviceFormatProperties(gpu, format, &fp);
        for (VkImageTiling tiling : {VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_TILING_LINEAR}) {
            const VkFormatFeatureFlags features = tiling == VK_IMAGE_TILING_OPTIMAL ? fp.optimalTilingFeatures : fp.linearTilingFeatures;
            if (!(features & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT)) continue;
            ii.tiling = tiling;
            if (vkCreateImage(device, &ii, nullptr, &srcImage) == VK_SUCCESS) break;
            srcImage = VK_NULL_HANDLE;
        }
        if (!srcImage) return "RCD output cannot be a storage image on this GPU";
        VkImportAndroidHardwareBufferInfoANDROID import{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
        import.buffer = ahb;
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        dedicated.pNext = &import;
        dedicated.image = srcImage;
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ai.pNext = &dedicated;
        ai.allocationSize = props.allocationSize;
        const int32_t type = memoryType(props.memoryTypeBits, 0);
        if (type < 0) return "source memory type";
        ai.memoryTypeIndex = static_cast<uint32_t>(type);
        if (vkAllocateMemory(device, &ai, nullptr, &srcMemory) != VK_SUCCESS) return "source import";
        vkBindImageMemory(device, srcImage, srcMemory, 0);
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vi.image = srcImage;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = format;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        if (vkCreateImageView(device, &vi, nullptr, &srcView) != VK_SUCCESS) return "source view";
        AHardwareBuffer_acquire(ahb);
        srcAhb = ahb;
        return "";
    }

    VkPipeline pipeline(const std::vector<uint32_t>& spv, VkPipelineLayout layout) {
        VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        mi.codeSize = spv.size() * 4;
        mi.pCode = spv.data();
        VkShaderModule module;
        if (vkCreateShaderModule(device, &mi, nullptr, &module) != VK_SUCCESS) return VK_NULL_HANDLE;
        VkComputePipelineCreateInfo pi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        pi.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, module, "main", nullptr};
        pi.layout = layout;
        VkPipeline pipe = VK_NULL_HANDLE;
        vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pi, nullptr, &pipe);
        vkDestroyShaderModule(device, module, nullptr);
        return pipe;
    }

    VkDescriptorSetLayout setLayout(const std::vector<VkDescriptorType>& types) {
        std::vector<VkDescriptorSetLayoutBinding> b(types.size());
        for (size_t i = 0; i < types.size(); ++i)
            b[i] = {static_cast<uint32_t>(i), types[i], 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        li.bindingCount = static_cast<uint32_t>(b.size());
        li.pBindings = b.data();
        VkDescriptorSetLayout layout = VK_NULL_HANDLE;
        vkCreateDescriptorSetLayout(device, &li, nullptr, &layout);
        return layout;
    }

    std::string submitAndWait() {
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        vkResetFences(device, 1, &fence);
        if (vkQueueSubmit(queue, 1, &si, fence) != VK_SUCCESS) return "submit";
        if (vkWaitForFences(device, 1, &fence, VK_TRUE, 5'000'000'000ull) != VK_SUCCESS) return "GPU timeout";
        return "";
    }
};

Engine::Engine() : d_(new Impl) {}

Engine::~Engine() {
    Impl& d = *d_;
    if (d.device) {
        vkDeviceWaitIdle(d.device);
        d.releaseSource();
        for (Buf* b : {&d.tables, &d.params, &d.processed, &d.luma, &d.chroma, &d.rgba}) d.release(*b);
        if (d.fence) vkDestroyFence(d.device, d.fence, nullptr);
        if (d.cmdPool) vkDestroyCommandPool(d.device, d.cmdPool, nullptr);
        if (d.pool) vkDestroyDescriptorPool(d.device, d.pool, nullptr);
        for (VkPipeline p : {d.gradePipe, d.outputPipe}) if (p) vkDestroyPipeline(d.device, p, nullptr);
        for (VkPipelineLayout l : {d.gradePipeLayout, d.outputPipeLayout}) if (l) vkDestroyPipelineLayout(d.device, l, nullptr);
        for (VkDescriptorSetLayout l : {d.gradeLayout, d.outputLayout}) if (l) vkDestroyDescriptorSetLayout(d.device, l, nullptr);
        vkDestroyDevice(d.device, nullptr);
    }
    if (d.instance) vkDestroyInstance(d.instance, nullptr);
    delete d_;
}

std::string Engine::init(const std::vector<uint32_t>& gradeSpv, const std::vector<uint32_t>& outputSpv) {
    Impl& d = *d_;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Cinematic RAW Studio";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    if (vkCreateInstance(&ci, nullptr, &d.instance) != VK_SUCCESS) return "instance";
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(d.instance, &n, nullptr);
    if (n == 0) return "no GPU";
    std::vector<VkPhysicalDevice> gpus(n);
    vkEnumeratePhysicalDevices(d.instance, &n, gpus.data());
    // As RawLens: scan every GPU for Vulkan 1.1, AHardwareBuffer import and a
    // compute queue (the first enumerated GPU can lack AHB on some Adreno devices).
    auto deviceHas = [](VkPhysicalDevice g, const char* name) {
        uint32_t count = 0;
        vkEnumerateDeviceExtensionProperties(g, nullptr, &count, nullptr);
        std::vector<VkExtensionProperties> all(count);
        vkEnumerateDeviceExtensionProperties(g, nullptr, &count, all.data());
        return std::any_of(all.begin(), all.end(), [&](auto& a) { return !strcmp(a.extensionName, name); });
    };
    for (VkPhysicalDevice g : gpus) {
        VkPhysicalDeviceProperties pp{};
        vkGetPhysicalDeviceProperties(g, &pp);
        if (pp.apiVersion < VK_API_VERSION_1_1 || !deviceHas(g, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)) continue;
        uint32_t count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(g, &count, nullptr);
        std::vector<VkQueueFamilyProperties> families(count);
        vkGetPhysicalDeviceQueueFamilyProperties(g, &count, families.data());
        if (std::any_of(families.begin(), families.end(), [](auto& f) { return (f.queueFlags & VK_QUEUE_COMPUTE_BIT) != 0; })) { d.gpu = g; break; }
    }
    if (!d.gpu) return "no GPU with Vulkan 1.1, AHardwareBuffer import and compute";
    vkGetPhysicalDeviceMemoryProperties(d.gpu, &d.memory);
    {
        VkPhysicalDeviceFeatures f{};
        vkGetPhysicalDeviceFeatures(d.gpu, &f);
        VkPhysicalDeviceProperties pp{};
        vkGetPhysicalDeviceProperties(d.gpu, &pp);
        __android_log_print(ANDROID_LOG_INFO, "MlvGpu", "%s: shaderFloat64=%u shaderInt64=%u", pp.deviceName, f.shaderFloat64, f.shaderInt64);
    }
    uint32_t qn = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(d.gpu, &qn, nullptr);
    std::vector<VkQueueFamilyProperties> q(qn);
    vkGetPhysicalDeviceQueueFamilyProperties(d.gpu, &qn, q.data());
    d.family = UINT32_MAX;
    for (uint32_t i = 0; i < qn; ++i)
        if (q[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { d.family = i; d.timestamps = q[i].timestampValidBits > 0; break; }
    if (d.family == UINT32_MAX) return "no compute queue";
    const float priority = 1.0f;
    VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qi.queueFamilyIndex = d.family;
    qi.queueCount = 1;
    qi.pQueuePriorities = &priority;
    uint32_t en = 0;
    vkEnumerateDeviceExtensionProperties(d.gpu, nullptr, &en, nullptr);
    std::vector<VkExtensionProperties> avail(en);
    vkEnumerateDeviceExtensionProperties(d.gpu, nullptr, &en, avail.data());
    auto has = [&](const char* e) { return std::any_of(avail.begin(), avail.end(), [&](auto& a) { return !strcmp(a.extensionName, e); }); };
    std::vector<const char*> exts = {VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME};
    if (!has(exts[0])) return "no AHardwareBuffer import";
    for (const char* e : {VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME, VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
                          VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME, VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME})
        if (has(e)) exts.push_back(e);
    // Ownership of the shared RCD output moves through FOREIGN (RawLens releases it there).
    d.foreign = has(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
    VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    di.queueCreateInfoCount = 1;
    di.pQueueCreateInfos = &qi;
    di.enabledExtensionCount = static_cast<uint32_t>(exts.size());
    di.ppEnabledExtensionNames = exts.data();
    if (vkCreateDevice(d.gpu, &di, nullptr, &d.device) != VK_SUCCESS) return "device";
    vkGetDeviceQueue(d.device, d.family, 0, &d.queue);
    d.getAhbProps = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(d.device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (!d.getAhbProps) return "AHardwareBuffer entry point";

    const VkDescriptorType SB = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    const VkDescriptorType UB = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    d.gradeLayout = d.setLayout({VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, SB, SB, UB});
    d.outputLayout = d.setLayout({SB, SB, SB, SB});
    VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pl.setLayoutCount = 1;
    pl.pSetLayouts = &d.gradeLayout;
    vkCreatePipelineLayout(d.device, &pl, nullptr, &d.gradePipeLayout);
    VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT, 0, 5 * sizeof(int32_t)};
    pl.pSetLayouts = &d.outputLayout;
    pl.pushConstantRangeCount = 1;
    pl.pPushConstantRanges = &push;
    vkCreatePipelineLayout(d.device, &pl, nullptr, &d.outputPipeLayout);
    d.gradePipe = d.pipeline(gradeSpv, d.gradePipeLayout);
    d.outputPipe = d.pipeline(outputSpv, d.outputPipeLayout);
    if (!d.gradePipe || !d.outputPipe) return "pipelines";

    VkDescriptorPoolSize sizes[3] = {{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1}, {SB, 6}, {UB, 1}};
    VkDescriptorPoolCreateInfo pi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pi.maxSets = 2; pi.poolSizeCount = 3; pi.pPoolSizes = sizes;
    vkCreateDescriptorPool(d.device, &pi, nullptr, &d.pool);
    VkDescriptorSetLayout layouts[2] = {d.gradeLayout, d.outputLayout};
    VkDescriptorSet sets[2];
    VkDescriptorSetAllocateInfo sa{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    sa.descriptorPool = d.pool; sa.descriptorSetCount = 2; sa.pSetLayouts = layouts;
    if (vkAllocateDescriptorSets(d.device, &sa, sets) != VK_SUCCESS) return "descriptor sets";
    d.gradeSet = sets[0]; d.outputSet = sets[1];

    VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cp.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cp.queueFamilyIndex = d.family;
    vkCreateCommandPool(d.device, &cp, nullptr, &d.cmdPool);
    VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ca.commandPool = d.cmdPool; ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ca.commandBufferCount = 1;
    vkAllocateCommandBuffers(d.device, &ca, &d.cmd);
    VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    vkCreateFence(d.device, &fi, nullptr, &d.fence);
    return "";
}

std::string Engine::run(const processingObject_t* p, AHardwareBuffer* src, int w, int h, int outW, int outH, bool wantRgba) {
    Impl& d = *d_;
    if (!d.device) return "not initialised";
    if (!supports(p)) return "settings need the CPU path";
    if (outW < 2 || outH < 2 || (outW & 1) || (outH & 1)) return "output size";
    if (std::string e = d.importSource(src, w, h); !e.empty()) return e;

    // MLV-App's own tables, packed as 32-bit words at fixed offsets.
    const auto c0 = std::chrono::steady_clock::now();
    std::vector<uint32_t> t;
    t.reserve(size_t(1) << 21);
    auto i32 = [&](const int32_t* a, size_t n) { auto o = int32_t(t.size()); for (size_t i = 0; i < n; ++i) t.push_back(uint32_t(a[i])); return o; };
    auto f32 = [&](auto a, size_t n) {
        auto o = int32_t(t.size());
        for (size_t i = 0; i < n; ++i) { float f = float(a[i]); uint32_t bits; std::memcpy(&bits, &f, 4); t.push_back(bits); }
        return o;
    };
    Params pr{};
    pr.width = w; pr.height = h;
    // Back-to-back lookups composed (exact; fewer reads per pixel).
    auto levelsThen = [&](const int32_t* pm) {
        auto o = int32_t(t.size());
        for (uint32_t x = 0; x < 65536; ++x) t.push_back(uint32_t(pm[p->pre_calc_levels[x]]));
        return o;
    };
    // uint16 tables: two entries per word (mlv_grade.comp u16()).
    auto packed = [&](auto entry) {
        auto o = int32_t(t.size());
        for (uint32_t x = 0; x < 65536; x += 2) t.push_back(uint32_t(entry(x)) | (uint32_t(entry(x + 1)) << 16));
        return o;
    };
    auto curvesThen = [&](const uint16_t* gc) {
        return packed([&](uint32_t x) { return gc[p->gcurve_y[p->pre_calc_curve_r[x]]]; });
    };
    pr.oPm0 = levelsThen(p->pre_calc_matrix[0]);
    pr.oPm4 = levelsThen(p->pre_calc_matrix[4]);
    pr.oPm8 = levelsThen(p->pre_calc_matrix[8]);
    pr.oGamma = packed([&](uint32_t x) { return p->pre_calc_gamma[x]; });
    pr.oOutR = curvesThen(p->gcurve_r);
    pr.oOutG = curvesThen(p->gcurve_g);
    pr.oOutB = curvesThen(p->gcurve_b);
    pr.oSat = i32(p->pre_calc_sat, 131072);
    pr.oVib = i32(p->pre_calc_vibrance, 131072);
    pr.oHueHue = f32(p->hue_vs_hue, 36000); pr.oHueSat = f32(p->hue_vs_saturation, 36000);
    pr.oHueLuma = f32(p->hue_vs_luma, 36000); pr.oLumaSat = f32(p->luma_vs_saturation, 36000);
    pr.oContrast = f32(p->contrast_curve, 65536);
    int32_t flags = 0;
    if (p->allow_creative_adjustments) {
        flags |= F_CREATIVE | F_TONING;  // MLV-App: toning_dry (0..1) < 99.8 always holds
        if (on(p->contrast)) flags |= F_CONTRAST;
        if (p->hue_vs_luma_used || p->hue_vs_saturation_used || p->hue_vs_hue_used || p->luma_vs_saturation_used) flags |= F_HUEVS;
        if (p->vibrance > 1.01 || p->vibrance < 0.99) flags |= F_VIB | (p->vibrance > 1.0 ? F_VIB_POS : 0);
        if (p->saturation > 1.01 || p->saturation < 0.99) flags |= F_SAT;
    }
    if (p->use_cam_matrix > 0) flags |= F_CAM;
    if (p->exr_mode) flags |= F_EXR;
    if (p->AgX) flags |= F_AGX;
    if (p->highlight_reconstruction) flags |= F_HIGHLIGHTS;
    if (p->vignette_strength != 0 && p->vignette_mask && p->vignette_end == p->vignette_mask + size_t(w) * h) {
        flags |= F_VIGNETTE;
        pr.oVignette = f32(p->vignette_mask, size_t(w) * h);
    }
    pr.flags = flags;
    pr.highestGreen = p->highest_green;
    pr.vignetteStrength = p->vignette_strength;
    pr.toningDry = p->toning_dry;
    for (int i = 0; i < 3; ++i) pr.toningWet[i] = p->toning_wet[i];
    double agx[9], agxInv[9], gamut[9], gamutInv[9];
    std::copy(kAgx, kAgx + 9, agx);
    invertMatrix(agx, agxInv);
    std::copy(kMlvColourGamuts[p->colour_gamut], kMlvColourGamuts[p->colour_gamut] + 9, gamut);
    invertMatrix(gamut, gamutInv);
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) {
            pr.wb[r * 4 + c] = float(p->proper_wb_matrix[r * 3 + c]);
            pr.agx[r * 4 + c] = float(kAgx[r * 3 + c]);
            pr.agxInv[r * 4 + c] = float(agxInv[r * 3 + c]);
        }
    for (int i = 0; i < 3; ++i) pr.rgbToY[i] = float(gamutInv[3 + i]);

    if (!d.ensure(d.tables, t.size() * 4, true) || !d.ensure(d.params, sizeof pr, true) ||
        !d.ensure(d.processed, size_t(w) * h * 8, false) || !d.ensureReadback(d.luma, size_t(outW) * outH * 2) ||
        !d.ensureReadback(d.chroma, size_t(outW) * outH) || !d.ensureReadback(d.rgba, size_t(outW) * outH * 4))
        return "buffers";
    std::memcpy(d.tables.map, t.data(), t.size() * 4);
    std::memcpy(d.params.map, &pr, sizeof pr);

    VkDescriptorImageInfo img{VK_NULL_HANDLE, d.srcView, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorBufferInfo bt{d.tables.buffer, 0, VK_WHOLE_SIZE}, bp{d.params.buffer, 0, VK_WHOLE_SIZE},
        bproc{d.processed.buffer, 0, VK_WHOLE_SIZE}, by{d.luma.buffer, 0, VK_WHOLE_SIZE},
        buv{d.chroma.buffer, 0, VK_WHOLE_SIZE}, brgba{d.rgba.buffer, 0, VK_WHOLE_SIZE};
    const VkDescriptorType SB = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    VkWriteDescriptorSet wr[8] = {
        {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, d.gradeSet, 0, 0, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &img, nullptr, nullptr},
        {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, d.gradeSet, 1, 0, 1, SB, nullptr, &bt, nullptr},
        {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, d.gradeSet, 2, 0, 1, SB, nullptr, &bproc, nullptr},
        {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, d.gradeSet, 3, 0, 1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, nullptr, &bp, nullptr},
        {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, d.outputSet, 0, 0, 1, SB, nullptr, &bproc, nullptr},
        {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, d.outputSet, 1, 0, 1, SB, nullptr, &by, nullptr},
        {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, d.outputSet, 2, 0, 1, SB, nullptr, &buv, nullptr},
        {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, d.outputSet, 3, 0, 1, SB, nullptr, &brgba, nullptr},
    };
    vkUpdateDescriptorSets(d.device, 8, wr, 0, nullptr);

    vkResetCommandBuffer(d.cmd, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(d.cmd, &bi);
    // The RCD output was written (and waited for) on RawLens's Vulkan device,
    // which releases it to FOREIGN in GENERAL layout. Acquire it the same way:
    // keeping GENERAL preserves the contents (UNDEFINED may discard them on
    // drivers that compress images, e.g. Adreno UBWC). Released back after use.
    VkImageMemoryBarrier ib{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    ib.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    ib.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    ib.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    ib.srcQueueFamilyIndex = d.foreign ? VK_QUEUE_FAMILY_FOREIGN_EXT : VK_QUEUE_FAMILY_IGNORED;
    ib.dstQueueFamilyIndex = d.foreign ? d.family : VK_QUEUE_FAMILY_IGNORED;
    ib.image = d.srcImage;
    ib.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(d.cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &ib);
    // GPU timestamps (debug timing) only where the queue supports them.
    if (d.timestamps && !d.timer) {
        VkQueryPoolCreateInfo qp{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
        qp.queryType = VK_QUERY_TYPE_TIMESTAMP;
        qp.queryCount = 3;
        vkCreateQueryPool(d.device, &qp, nullptr, &d.timer);
        VkPhysicalDeviceProperties pp{};
        vkGetPhysicalDeviceProperties(d.gpu, &pp);
        d.tickNs = pp.limits.timestampPeriod;
    }
    if (d.timer) {
        vkCmdResetQueryPool(d.cmd, d.timer, 0, 3);
        vkCmdWriteTimestamp(d.cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, d.timer, 0);
    }
    vkCmdBindPipeline(d.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d.gradePipe);
    vkCmdBindDescriptorSets(d.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d.gradePipeLayout, 0, 1, &d.gradeSet, 0, nullptr);
    vkCmdDispatch(d.cmd, uint32_t(w + 15) / 16, uint32_t(h + 7) / 8, 1);
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT};
    vkCmdPipelineBarrier(d.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
    // Grade pass done with the RCD output: hand it back to FOREIGN for RawLens's next frame.
    if (d.foreign) {
        VkImageMemoryBarrier rb = ib;
        rb.srcAccessMask = VK_ACCESS_SHADER_READ_BIT; rb.dstAccessMask = 0;
        rb.srcQueueFamilyIndex = d.family; rb.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        vkCmdPipelineBarrier(d.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &rb);
    }
    if (d.timer) vkCmdWriteTimestamp(d.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, d.timer, 1);
    vkCmdBindPipeline(d.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d.outputPipe);
    vkCmdBindDescriptorSets(d.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, d.outputPipeLayout, 0, 1, &d.outputSet, 0, nullptr);
    const int32_t push[5] = {w, h, outW, outH, wantRgba ? 1 : 0};
    vkCmdPushConstants(d.cmd, d.outputPipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof push, push);
    vkCmdDispatch(d.cmd, uint32_t(outW / 2 + 15) / 16, uint32_t(outH / 2 + 7) / 8, 1);
    if (d.timer) vkCmdWriteTimestamp(d.cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, d.timer, 2);
    VkMemoryBarrier hb{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(d.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &hb, 0, nullptr, 0, nullptr);
    vkEndCommandBuffer(d.cmd);
    const auto c1 = std::chrono::steady_clock::now();
    if (std::string e = d.submitAndWait(); !e.empty()) return e;
    const auto c2 = std::chrono::steady_clock::now();
    using ms = std::chrono::duration<double, std::milli>;
    uint64_t ts[3] = {};
    if (d.timer) vkGetQueryPoolResults(d.device, d.timer, 0, 3, sizeof ts, ts, 8, VK_QUERY_RESULT_64_BIT);
    __android_log_print(ANDROID_LOG_DEBUG, "MlvGpu", "setup %.1f ms, wait %.1f ms, grade pass %.1f ms, output pass %.1f ms",
                        ms(c1 - c0).count(), ms(c2 - c1).count(), (ts[1] - ts[0]) * d.tickNs / 1e6, (ts[2] - ts[1]) * d.tickNs / 1e6);
    d.invalidate(d.luma);
    d.invalidate(d.chroma);
    if (wantRgba) d.invalidate(d.rgba);
    d.lastW = w; d.lastH = h;
    return "";
}

const uint16_t* Engine::lumaPlane() const { return static_cast<const uint16_t*>(d_->luma.map); }
const uint16_t* Engine::chromaPlane() const { return static_cast<const uint16_t*>(d_->chroma.map); }
const uint8_t* Engine::rgba() const { return static_cast<const uint8_t*>(d_->rgba.map); }

std::vector<uint32_t> Engine::readProcessed() const {
    Impl& d = *d_;
    const size_t bytes = size_t(d.lastW) * d.lastH * 8;
    if (!bytes) return {};
    Buf staging;
    if (!d.ensure(staging, bytes, true)) return {};
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkResetCommandBuffer(d.cmd, 0);
    vkBeginCommandBuffer(d.cmd, &bi);
    VkBufferCopy region{0, 0, bytes};
    vkCmdCopyBuffer(d.cmd, d.processed.buffer, staging.buffer, 1, &region);
    VkMemoryBarrier hb{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(d.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &hb, 0, nullptr, 0, nullptr);
    vkEndCommandBuffer(d.cmd);
    std::vector<uint32_t> out;
    if (d.submitAndWait().empty()) {
        out.resize(bytes / 4);
        std::memcpy(out.data(), staging.map, bytes);
    }
    d.release(staging);
    return out;
}

}  // namespace mlvgpu
