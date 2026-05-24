#!/usr/bin/env swift
//
// Generates the App Store-ready 1024×1024 icon for TacticalMaps.
// Xcode synthesises the smaller sizes from the single 1024 PNG via the
// asset catalog's universal slot.
//
// Run:  swift scripts/generate_icon.swift  (from the project root)
//

import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let size: CGFloat = 1024
let cs = CGColorSpaceCreateDeviceRGB()
let bmpInfo = CGImageAlphaInfo.premultipliedLast.rawValue
guard let ctx = CGContext(
    data: nil,
    width: Int(size), height: Int(size),
    bitsPerComponent: 8, bytesPerRow: 0,
    space: cs, bitmapInfo: bmpInfo
) else { fatalError("context") }

// Flip Y so coordinates are top-left origin (read more naturally).
ctx.translateBy(x: 0, y: size)
ctx.scaleBy(x: 1, y: -1)

// --- Background: dark slate gradient ---
let bgColors = [
    CGColor(red: 0.08, green: 0.10, blue: 0.13, alpha: 1.0),
    CGColor(red: 0.13, green: 0.17, blue: 0.21, alpha: 1.0)
] as CFArray
let gradient = CGGradient(colorsSpace: cs, colors: bgColors, locations: [0.0, 1.0])!
ctx.drawLinearGradient(
    gradient,
    start: .zero,
    end: CGPoint(x: size, y: size),
    options: []
)

// --- Subtle grid pattern ---
ctx.setStrokeColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.04))
ctx.setLineWidth(1.5)
let step: CGFloat = 64
var x: CGFloat = step
while x < size {
    ctx.move(to: CGPoint(x: x, y: 0))
    ctx.addLine(to: CGPoint(x: x, y: size))
    x += step
}
var y: CGFloat = step
while y < size {
    ctx.move(to: CGPoint(x: 0, y: y))
    ctx.addLine(to: CGPoint(x: size, y: y))
    y += step
}
ctx.strokePath()

// Stronger crosshair midlines (mark the "true" centre grid)
ctx.setStrokeColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.08))
ctx.setLineWidth(2)
ctx.move(to: CGPoint(x: size/2, y: 0))
ctx.addLine(to: CGPoint(x: size/2, y: size))
ctx.move(to: CGPoint(x: 0, y: size/2))
ctx.addLine(to: CGPoint(x: size, y: size/2))
ctx.strokePath()

// --- Tactical compass crosshair ---
let cx = size / 2
let cy = size / 2
let orange = CGColor(red: 1.0, green: 0.65, blue: 0.18, alpha: 1.0)
let armLen: CGFloat = 320
ctx.setStrokeColor(orange)
ctx.setLineWidth(20)
ctx.setLineCap(.round)
ctx.move(to: CGPoint(x: cx - armLen, y: cy))
ctx.addLine(to: CGPoint(x: cx + armLen, y: cy))
ctx.move(to: CGPoint(x: cx, y: cy - armLen))
ctx.addLine(to: CGPoint(x: cx, y: cy + armLen))
ctx.strokePath()

// Centre ring
ctx.setStrokeColor(orange)
ctx.setLineWidth(18)
let ringR: CGFloat = 200
ctx.strokeEllipse(in: CGRect(x: cx - ringR, y: cy - ringR, width: ringR*2, height: ringR*2))

// Centre dot
ctx.setFillColor(orange)
ctx.fillEllipse(in: CGRect(x: cx - 24, y: cy - 24, width: 48, height: 48))

// --- North marker (red triangle pointing up) ---
let red = CGColor(red: 0.92, green: 0.22, blue: 0.22, alpha: 1.0)
ctx.setFillColor(red)
ctx.move(to: CGPoint(x: cx, y: cy - armLen - 90))
ctx.addLine(to: CGPoint(x: cx - 60, y: cy - armLen + 10))
ctx.addLine(to: CGPoint(x: cx + 60, y: cy - armLen + 10))
ctx.closePath()
ctx.fillPath()

// --- Tactical green corner accents (MGRS-readout green) ---
let green = CGColor(red: 0.55, green: 0.95, blue: 0.55, alpha: 1.0)
ctx.setStrokeColor(green)
ctx.setLineWidth(16)
ctx.setLineCap(.round)
let bracket: CGFloat = 90
let margin: CGFloat = 140
// Top-left
ctx.move(to: CGPoint(x: margin, y: margin + bracket))
ctx.addLine(to: CGPoint(x: margin, y: margin))
ctx.addLine(to: CGPoint(x: margin + bracket, y: margin))
// Top-right
ctx.move(to: CGPoint(x: size - margin - bracket, y: margin))
ctx.addLine(to: CGPoint(x: size - margin, y: margin))
ctx.addLine(to: CGPoint(x: size - margin, y: margin + bracket))
// Bottom-left
ctx.move(to: CGPoint(x: margin, y: size - margin - bracket))
ctx.addLine(to: CGPoint(x: margin, y: size - margin))
ctx.addLine(to: CGPoint(x: margin + bracket, y: size - margin))
// Bottom-right
ctx.move(to: CGPoint(x: size - margin - bracket, y: size - margin))
ctx.addLine(to: CGPoint(x: size - margin, y: size - margin))
ctx.addLine(to: CGPoint(x: size - margin, y: size - margin - bracket))
ctx.strokePath()

// --- Save PNG ---
guard let image = ctx.makeImage() else { fatalError("image") }
let outPath = CommandLine.arguments.count > 1
    ? CommandLine.arguments[1]
    : "ios/TacticalMaps/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
let outURL = URL(fileURLWithPath: outPath)
try? FileManager.default.createDirectory(
    at: outURL.deletingLastPathComponent(),
    withIntermediateDirectories: true
)
guard let dest = CGImageDestinationCreateWithURL(
    outURL as CFURL,
    UTType.png.identifier as CFString,
    1, nil
) else { fatalError("dest") }
CGImageDestinationAddImage(dest, image, nil)
CGImageDestinationFinalize(dest)
print("✓ Wrote \(outURL.path)")
