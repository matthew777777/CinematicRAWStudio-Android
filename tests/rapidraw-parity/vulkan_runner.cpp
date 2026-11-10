// Standalone Android Vulkan buffer runner. No application or legacy processing code.
#include <vulkan/vulkan.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

static void check(VkResult r) { if (r != VK_SUCCESS) throw std::runtime_error("Vulkan error " + std::to_string(r)); }
static std::vector<uint32_t> read(const char* path) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f || f.tellg() <= 0 || f.tellg() % 4) throw std::runtime_error("Invalid input file");
    std::vector<uint32_t> data(size_t(f.tellg()) / 4); f.seekg(0);
    f.read(reinterpret_cast<char*>(data.data()), data.size() * 4); return data;
}

int main(int argc, char** argv) {
    try {
        if (argc != 4) throw std::runtime_error("Usage: runner shader.spv input.f32 output.f32");
        const auto spv = read(argv[1]), input = read(argv[2]);
        if (input.size() % 4) throw std::runtime_error("Input must contain vec4 records");
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo = &app;
        VkInstance instance; check(vkCreateInstance(&ici, nullptr, &instance));
        uint32_t n = 0; check(vkEnumeratePhysicalDevices(instance, &n, nullptr));
        if (!n) throw std::runtime_error("No Vulkan device");
        std::vector<VkPhysicalDevice> devices(n); check(vkEnumeratePhysicalDevices(instance, &n, devices.data()));
        auto physical = devices[0];
        VkPhysicalDeviceProperties props; vkGetPhysicalDeviceProperties(physical, &props);
        std::printf("device=%s vendor=%u driver=%u api=%u\n", props.deviceName, props.vendorID, props.driverVersion, props.apiVersion);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &n, nullptr);
        std::vector<VkQueueFamilyProperties> families(n); vkGetPhysicalDeviceQueueFamilyProperties(physical, &n, families.data());
        uint32_t family = 0; while (family < n && !(families[family].queueFlags & VK_QUEUE_COMPUTE_BIT)) ++family;
        if (family == n) throw std::runtime_error("No compute queue");
        float priority = 1;
        VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qi.queueFamilyIndex = family; qi.queueCount = 1; qi.pQueuePriorities = &priority;
        VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; di.queueCreateInfoCount = 1; di.pQueueCreateInfos = &qi;
        VkDevice device; check(vkCreateDevice(physical, &di, nullptr, &device));
        VkQueue queue; vkGetDeviceQueue(device, family, 0, &queue);
        VkPhysicalDeviceMemoryProperties memory; vkGetPhysicalDeviceMemoryProperties(physical, &memory);
        VkBuffer buffers[2]{}; VkDeviceMemory allocations[2]{}; void* maps[2]{};
        const VkDeviceSize bytes = input.size() * 4;
        for (int i = 0; i < 2; ++i) {
            VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; bi.size = bytes; bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            check(vkCreateBuffer(device, &bi, nullptr, &buffers[i]));
            VkMemoryRequirements req; vkGetBufferMemoryRequirements(device, buffers[i], &req);
            uint32_t type = 0;
            const auto flags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            while (type < memory.memoryTypeCount && (!(req.memoryTypeBits & (1u << type)) || (memory.memoryTypes[type].propertyFlags & flags) != flags)) ++type;
            if (type == memory.memoryTypeCount) throw std::runtime_error("No coherent host memory");
            VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.allocationSize = req.size; ai.memoryTypeIndex = type;
            check(vkAllocateMemory(device, &ai, nullptr, &allocations[i])); check(vkBindBufferMemory(device, buffers[i], allocations[i], 0));
            check(vkMapMemory(device, allocations[i], 0, VK_WHOLE_SIZE, 0, &maps[i]));
            std::memset(maps[i], 0xcd, bytes);
        }
        std::memcpy(maps[0], input.data(), bytes);
        VkDescriptorSetLayoutBinding bindings[2]{};
        for (int i = 0; i < 2; ++i) bindings[i] = {uint32_t(i), VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; li.bindingCount = 2; li.pBindings = bindings;
        VkDescriptorSetLayout setLayout; check(vkCreateDescriptorSetLayout(device, &li, nullptr, &setLayout));
        VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; pli.setLayoutCount = 1; pli.pSetLayouts = &setLayout;
        VkPipelineLayout layout; check(vkCreatePipelineLayout(device, &pli, nullptr, &layout));
        VkShaderModuleCreateInfo si{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; si.codeSize = spv.size() * 4; si.pCode = spv.data();
        VkShaderModule shader; check(vkCreateShaderModule(device, &si, nullptr, &shader));
        VkComputePipelineCreateInfo pi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO}; pi.layout = layout;
        pi.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, shader, "main", nullptr};
        VkPipeline pipeline; check(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pi, nullptr, &pipeline));
        VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2};
        VkDescriptorPoolCreateInfo pci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; pci.maxSets = 1; pci.poolSizeCount = 1; pci.pPoolSizes = &poolSize;
        VkDescriptorPool pool; check(vkCreateDescriptorPool(device, &pci, nullptr, &pool));
        VkDescriptorSetAllocateInfo sai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; sai.descriptorPool = pool; sai.descriptorSetCount = 1; sai.pSetLayouts = &setLayout;
        VkDescriptorSet set; check(vkAllocateDescriptorSets(device, &sai, &set));
        for (int i = 0; i < 2; ++i) {
            VkDescriptorBufferInfo bi{buffers[i], 0, bytes};
            VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}; write.dstSet = set; write.dstBinding = i; write.descriptorCount = 1; write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; write.pBufferInfo = &bi;
            vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);
        }
        VkCommandPoolCreateInfo ci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; ci.queueFamilyIndex = family;
        VkCommandPool commands; check(vkCreateCommandPool(device, &ci, nullptr, &commands));
        VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cai.commandPool = commands; cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount = 1;
        VkCommandBuffer cmd; check(vkAllocateCommandBuffers(device, &cai, &cmd));
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; check(vkBeginCommandBuffer(cmd, &begin));
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 1, &set, 0, nullptr);
        vkCmdDispatch(cmd, (input.size() / 4 + 63) / 64, 1, 1);
        VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &barrier, 0, nullptr, 0, nullptr);
        check(vkEndCommandBuffer(cmd));
        VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; VkFence fence; check(vkCreateFence(device, &fi, nullptr, &fence));
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.commandBufferCount = 1; submit.pCommandBuffers = &cmd;
        check(vkQueueSubmit(queue, 1, &submit, fence)); check(vkWaitForFences(device, 1, &fence, VK_TRUE, 10000000000ull));
        std::ofstream out(argv[3], std::ios::binary); out.write(static_cast<char*>(maps[1]), bytes); if (!out) throw std::runtime_error("Output write failed");
        vkDestroyFence(device, fence, nullptr); vkDestroyCommandPool(device, commands, nullptr);
        vkDestroyDescriptorPool(device, pool, nullptr); vkDestroyPipeline(device, pipeline, nullptr);
        vkDestroyShaderModule(device, shader, nullptr); vkDestroyPipelineLayout(device, layout, nullptr); vkDestroyDescriptorSetLayout(device, setLayout, nullptr);
        for (int i = 0; i < 2; ++i) { vkUnmapMemory(device, allocations[i]); vkDestroyBuffer(device, buffers[i], nullptr); vkFreeMemory(device, allocations[i], nullptr); }
        vkDestroyDevice(device, nullptr); vkDestroyInstance(instance, nullptr);
        return 0;
    } catch (const std::exception& e) { std::fprintf(stderr, "%s\n", e.what()); return 1; }
}
