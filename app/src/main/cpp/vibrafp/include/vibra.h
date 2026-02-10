#ifndef INCLUDE_VIBRA_H_
#define INCLUDE_VIBRA_H_

#include <string>

extern "C"
{
/**
 * @brief Structure to hold a music fingerprint.
 *
 * @note The structure is thread-unsafe and does not require manual memory management
 * for the returned pointer.
 */
struct Fingerprint
{
    std::string uri;        /**< The URI associated with the fingerprint. */
    unsigned int sample_ms; /**< The sample duration in milliseconds. */
};

/**
 * @brief Generate a fingerprint from a music file.
 *
 * @param music_file_path The path to the music file.
 * @return Fingerprint* Pointer to the generated fingerprint.
 *
 * @note The returned pointer must be freed after use. See vibra_free_fingerprint().
 */
Fingerprint *vibra_get_fingerprint_from_music_file(const char *music_file_path);

/**
 * @brief Generate a fingerprint from WAV data.
 *
 * @param raw_wav The raw WAV data.
 * @param wav_data_size The size of the WAV data in bytes.
 * @return Fingerprint* Pointer to the generated fingerprint.
 *
 * @note The returned pointer must be freed after use. See vibra_free_fingerprint().
 */
Fingerprint *vibra_get_fingerprint_from_wav_data(const char *raw_wav, int wav_data_size);

/**
 * @brief Generate a fingerprint from signed PCM data.
 *
 * @param raw_pcm The raw PCM data.
 * @param pcm_data_size The size of the PCM data in bytes.
 * @param sample_rate The sample rate of the PCM data.
 * @param sample_width The sample width (bits per sample) of the PCM data.
 * @param channel_count The number of channels in the PCM data.
 * @return Fingerprint* Pointer to the generated fingerprint.
 *
 * @note The returned pointer must be freed after use. See vibra_free_fingerprint().
 */
Fingerprint *vibra_get_fingerprint_from_signed_pcm(const char *raw_pcm, int pcm_data_size,
                                                   int sample_rate, int sample_width,
                                                   int channel_count);

/**
 * @brief Generate a fingerprint from PCM data.
 *
 * @param raw_pcm The raw PCM data.
 * @param pcm_data_size The size of the PCM data in bytes.
 * @param sample_rate The sample rate of the PCM data.
 * @param sample_width The sample width (bits per sample) of the PCM data.
 * @param channel_count The number of channels in the PCM data.
 * @return Fingerprint* Pointer to the generated fingerprint.
 *
 * @note The returned pointer must be freed after use. See vibra_free_fingerprint().
 */
Fingerprint *vibra_get_fingerprint_from_float_pcm(const char *raw_pcm, int pcm_data_size,
                                                  int sample_rate, int sample_width,
                                                  int channel_count);

/**
 * @brief Get the URI associated with a fingerprint.
 *
 * @param fingerprint Pointer to the fingerprint.
 * @return const char* The URI as a C-string.
 *
 * @note The returned pointer should not be freed.
 */
const char *vibra_get_uri_from_fingerprint(Fingerprint *fingerprint);

/**
 * @brief Get the sample duration in milliseconds from a fingerprint.
 *
 * @param fingerprint Pointer to the fingerprint.
 * @return unsigned int The sample duration in milliseconds.
 */
unsigned int vibra_get_sample_ms_from_fingerprint(Fingerprint *fingerprint);

/**
 * @brief Free a fingerprint.
 *
 * @param fingerprint Pointer to the fingerprint.
 */
void vibra_free_fingerprint(Fingerprint *fingerprint);
} // extern "C"

#endif // INCLUDE_VIBRA_H_
