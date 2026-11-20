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

using SecIdentityRef = const struct TestIdentity*;
using SecCertificateRef = const struct TestCertificate*;
using SecKeyRef = const struct TestKey*;
using CFDataRef = const void*;
extern CFTypeRef kSecReturnAttributes, kSecAttrAccessGroup, kSecClassIdentity,
    kSecClassCertificate, kSecReturnRef, kSecMatchLimitAll, kSecAttrLabel;
constexpr OSStatus errSecDuplicateItem = -2;
OSStatus SecIdentityCopyCertificate(SecIdentityRef, SecCertificateRef*);
OSStatus SecIdentityCopyPrivateKey(SecIdentityRef, SecKeyRef*);
CFDataRef SecCertificateCopyData(SecCertificateRef);
CFDataRef SecKeyCopyExternalRepresentation(SecKeyRef, CFTypeRef*);
void CFRelease(CFTypeRef);
