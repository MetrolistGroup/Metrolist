#ifndef LIB_UTILS_CRC32_H_
#define LIB_UTILS_CRC32_H_

namespace crc32
{
std::uint32_t crc32(const char *buf, std::size_t len)
{
    std::uint32_t crc_table[256];
    std::uint32_t crc;
    std::size_t i, j;

    for (i = 0; i < 256; i++)
    {
        crc = i;
        for (j = 0; j < 8; j++)
            crc = crc & 1 ? (crc >> 1) ^ 0xEDB88320UL : crc >> 1;
        crc_table[i] = crc;
    };
    crc = 0xFFFFFFFFUL;

    while (len--)
        crc = crc_table[(crc ^ *buf++) & 0xFF] ^ (crc >> 8);

    return crc ^ 0xFFFFFFFFUL;
}
} // namespace crc32

#endif // LIB_UTILS_CRC32_H_
