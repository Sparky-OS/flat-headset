// SPDX-License-Identifier: GPL-3.0-or-later
// Authored syntax-test doubles only; no keychain operation is implemented.
#pragma once
#import <UIKit/UIKit.h>
using CFTypeRef = const void*;
using CFDictionaryRef = const void*;
using OSStatus = int;
extern CFTypeRef kSecClass, kSecClassGenericPassword, kSecAttrService, kSecAttrAccount,
    kSecReturnData, kSecMatchLimit, kSecMatchLimitOne, kSecValueData,
    kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly;
constexpr OSStatus errSecSuccess = 0, errSecItemNotFound = -1;
OSStatus SecItemCopyMatching(CFDictionaryRef, CFTypeRef*);
OSStatus SecItemUpdate(CFDictionaryRef, CFDictionaryRef);
OSStatus SecItemAdd(CFDictionaryRef, CFTypeRef*);
#define CFBridgingRelease(value) ((__bridge_transfer id)(value))
