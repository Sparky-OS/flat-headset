// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <string>
#include <vector>
// Bounded protobuf adapter for Cardboard's DeviceParams schema, not generated code.
namespace cardboard {
struct DeviceParams {
    enum { BOTTOM=0, CENTER=1, TOP=2 };
    std::string vendor, model;
    float distance=0, separation=0, tray=0;
    int alignment=BOTTOM;
    std::vector<float> coefficients, fov;
    float screen_to_lens_distance() const {return distance;}
    float inter_lens_distance() const {return separation;}
    float tray_to_lens_distance() const {return tray;}
    int vertical_alignment() const {return alignment;}
    int distortion_coefficients_size() const {return static_cast<int>(coefficients.size());}
    float distortion_coefficients(int i) const {return coefficients.at(i);}
    float left_eye_field_of_view_angles(int i) const {return fov.at(i);}
};
}
