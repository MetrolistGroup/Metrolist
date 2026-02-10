#ifndef LIB_AUDIO_BYTE_CONTROL_H_
#define LIB_AUDIO_BYTE_CONTROL_H_

#include <cstdint>

#define GETINTX(T, cp, i) (*(T *)((unsigned char *)(cp) + (i))) // NOLINT [readability-casting]
#define GETINT8(cp, i) GETINTX(std::int8_t, (cp), (i))
#define GETINT16(cp, i) GETINTX(std::int16_t, (cp), (i))
#define GETINT32(cp, i) GETINTX(std::int32_t, (cp), (i))
#define GETINT64(cp, i) GETINTX(std::int64_t, (cp), (i))

#ifdef WORDS_BIGENDIAN
#define GETINT24(cp, i)                                                                            \
    (((unsigned char *)(cp) + (i))[2] + (((unsigned char *)(cp) + (i))[1] * (1 << 8)) +            \
     (((signed char *)(cp) + (i))[0] * (1 << 16)))
#else
#define GETINT24(cp, i)                                                                            \
    (((unsigned char *)(cp) + (i))[0] + (((unsigned char *)(cp) + (i))[1] * (1 << 8)) +            \
     (((signed char *)(cp) + (i))[2] * (1 << 16)))
#endif

#define GETSAMPLE64(size, cp, i)                                                                   \
    (((size) == 1)   ? (std::int64_t)GETINT8((cp), (i)) * (1LL << 56)                              \
     : ((size) == 2) ? (std::int64_t)GETINT16((cp), (i)) * (1LL << 48)                             \
     : ((size) == 3) ? (std::int64_t)GETINT24((cp), (i)) * (1LL << 40)                             \
     : ((size) == 4) ? (std::int64_t)GETINT32((cp), (i)) * (1LL << 32)                             \
                     : (std::int64_t)GETINT64((cp), (i)))

#endif // LIB_AUDIO_BYTE_CONTROL_H_
