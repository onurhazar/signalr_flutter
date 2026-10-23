// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "signalr_flutter",
    platforms: [
        .iOS("13.0")
    ],
    products: [
        .library(name: "signalr-flutter", targets: ["signalr_flutter"])
    ],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework")
    ],
    targets: [
        // Pigeon-generated Objective-C bindings (SignalrApi.h / SignalrApi.m).
        // Swift Package Manager cannot mix Swift and Objective-C in one target,
        // so the generated code lives in its own target that the Swift target imports.
        .target(
            name: "signalr_flutter_pigeon",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework")
            ],
            cSettings: [
                .headerSearchPath("include/signalr_flutter_pigeon")
            ]
        ),
        // Swift plugin implementation (SwiftR + SwiftSignalrFlutterPlugin) plus the
        // SignalR JavaScript that SwiftR loads into its WKWebView at runtime.
        .target(
            name: "signalr_flutter",
            dependencies: [
                "signalr_flutter_pigeon",
                .product(name: "FlutterFramework", package: "FlutterFramework")
            ],
            resources: [
                .copy("Resources/SwiftR.js"),
                .copy("Resources/jquery-2.1.3.min.js"),
                .copy("Resources/jquery.signalr-2.4.2.min.js"),
                .copy("Resources/jquery.signalr-2.4.3.min.js")
            ]
        )
    ]
)
