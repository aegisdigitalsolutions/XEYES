// swift-tools-version:5.9
import PackageDescription

// The iOS side is a package rather than a pile of files in an Xcode target so that the parts which
// have to agree with Kotlin byte-for-byte -- the CSV codec, the JSON encoding, the row checksums,
// the export engine -- can be compiled and tested off-device, including on Linux CI. Those parts
// touch no Apple framework and are deliberately kept that way.
let package = Package(
    name: "RFMapper",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "RFMapperCore", targets: ["RFMapperCore"]),
        .library(name: "RFMapperCollectorKit", targets: ["RFMapperCollectorKit"]),
    ],
    targets: [
        // Pure Swift, no Apple frameworks, no dependencies. Testable anywhere.
        .target(name: "RFMapperCore"),

        // Radio capture and persistence. Every Apple-framework import is behind `canImport`, so this
        // still compiles on Linux (as very little) and a syntax error cannot hide until someone
        // opens Xcode.
        .target(name: "RFMapperCollectorKit", dependencies: ["RFMapperCore"]),

        .testTarget(
            name: "RFMapperCoreTests",
            dependencies: ["RFMapperCore"],
            // The cross-language contract fixtures live at the repository root and are shared with
            // the Kotlin and Python sides; the tests locate them by walking up from #filePath.
            resources: []
        ),
        .testTarget(name: "RFMapperCollectorKitTests", dependencies: ["RFMapperCollectorKit"]),
    ]
)
