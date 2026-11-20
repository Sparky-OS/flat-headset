# SPDX-License-Identifier: GPL-3.0-or-later
add_library(viewer STATIC Viewer.cpp
    ../ThirdParty/Cardboard/lens_distortion.cc
    ../ThirdParty/Cardboard/distortion_mesh.cc
    ../ThirdParty/Cardboard/polynomial_radial_distortion.cc
    ../ThirdParty/Cardboard/util/matrix_4x4.cc
    ../ThirdParty/Cardboard/qrcode/cardboard_v1/cardboard_v1.cc)
target_compile_features(viewer PUBLIC cxx_std_17)
target_include_directories(viewer PRIVATE ../ThirdParty/Cardboard)
target_compile_options(viewer PRIVATE -Wall -Wextra -Werror)
