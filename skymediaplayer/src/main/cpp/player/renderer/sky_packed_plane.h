#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

// GLES2 uploads tightly packed rows; FFmpeg linesize may include padding.
inline const uint8_t *skyPackedPlane(const uint8_t *data, int pitch, int rowBytes,
                                     int rows, std::vector<uint8_t> &packed) {
    if (!data || rowBytes <= 0 || rows <= 0 || pitch < rowBytes) return nullptr;
    if (pitch == rowBytes) return data;
    packed.resize(static_cast<size_t>(rowBytes) * rows);
    for (int row = 0; row < rows; ++row)
        std::memcpy(packed.data() + static_cast<size_t>(row) * rowBytes,
                    data + static_cast<size_t>(row) * pitch, rowBytes);
    return packed.data();
}
