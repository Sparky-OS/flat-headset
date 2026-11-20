// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <string_view>
namespace flatheadset {
// A launch action only, never an authorization or a credential handoff.
inline bool isCatchLaunch(std::string_view url) { return url == "flatheadset://catch"; }
}
