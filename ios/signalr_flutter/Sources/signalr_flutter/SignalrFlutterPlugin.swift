#if SWIFT_PACKAGE
import Flutter

/// Swift Package Manager registration entry point.
///
/// Flutter registers the plugin class named in pubspec.yaml (`SignalrFlutterPlugin`).
/// Under CocoaPods that name belongs to the Objective-C shim in `ios/Classes`, which
/// forwards to `SwiftSignalrFlutterPlugin`. The shim cannot be compiled by SwiftPM
/// (it imports the CocoaPods-generated `signalr_flutter-Swift.h`), so this subclass
/// exposes the same Objective-C class name directly from the Swift module instead.
/// It adds no behaviour: `register(with:)` is inherited unchanged.
@objc(SignalrFlutterPlugin)
public final class SignalrFlutterPlugin: SwiftSignalrFlutterPlugin {}
#endif
