import AppKit

// Run from the repository root: swift tools/icon/generate.swift
let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
let artwork = NSImage(contentsOf: root.appendingPathComponent("tools/icon/source.svg"))!
func render(_ size: Int, foreground: Bool, path: String) throws {
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
        bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
        colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
    NSGraphicsContext.current?.imageInterpolation = .high
    let canvas = CGFloat(size)
    if !foreground {
        NSColor(srgbRed: 0.055, green: 0.065, blue: 0.08, alpha: 1).setFill()
        NSRect(x: 0, y: 0, width: canvas, height: canvas).fill()
    }
    let height = canvas * (foreground ? 0.46 : 0.69)
    let width = height * 2840 / 3318
    artwork.draw(in: NSRect(x: (canvas - width) / 2, y: (canvas - height) / 2, width: width, height: height))
    NSGraphicsContext.restoreGraphicsState()
    let output = root.appendingPathComponent(path)
    try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
    try bitmap.representation(using: .png, properties: [:])!.write(to: output)
}
try render(432, foreground: true, path: "app/src/main/res/drawable-nodpi/ic_launcher_foreground.png")
for (density, size) in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)] {
    try render(size, foreground: false, path: "app/src/main/res/mipmap-\(density)/ic_launcher.png")
}
try render(512, foreground: false, path: "tools/icon/icon-512.png")
