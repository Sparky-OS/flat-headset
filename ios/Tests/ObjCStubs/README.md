# Linux Objective-C++ syntax check

Run `bash ios/Tools/check_objc_syntax.sh` with Clang, the C++ standard library headers and Linux development headers installed.
It parses the complete `Desktop.mm`, `Computers.mm` and `ViewerSettings.mm` translation units, including `Desktop.h`, with C++17, ARC, blocks and warnings as errors.
It checks Objective-C instance-variable declarations, C++ members and captures, method bodies, nested blocks and calls against the declarations here.
It does not link or run anything.

These are our own minimal test doubles, written from the receiver's call sites without Apple's headers.
They deliberately do not promise the real SDK's types, values, layouts, availability, ownership annotations or API completeness.
They cannot validate SDK compatibility, linking, Metal shaders, UIKit behavior, hardware decoding or a phone session.
The hosted Xcode build against the real SDK remains required.
Only the Linux check adds this include directory, and a dedicated macro guards against accidental product use.
Keep new declarations limited to what the checked sources need; never copy SDK headers here.

An Objective-C instance-variable block is not a C++ struct: `NSUInteger _presentationEpoch=0;` is invalid there.
Clang rejects that declaration with `expected ';' at end of declaration list` before and accepts `_presentationEpoch;` after the correction.
Objective-C allocation zeroes scalar instance variables; any nonzero initialization belongs in the initializer method.
