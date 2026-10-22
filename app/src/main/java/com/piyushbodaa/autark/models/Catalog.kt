package com.piyushbodaa.autark.models

data class CatalogModel(
    val id: String,
    val name: String,
    val repo: String,
    val revision: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val licenseUrl: String,
) {
    /** Pinned to a commit so the checksum below always matches what gets downloaded. */
    val url get() = "https://huggingface.co/$repo/resolve/$revision/$fileName"
}

/**
 * Autark picks the model; people never have to. Qwen 3.5 is Apache-licensed, speaks many
 * languages (Indian ones included) and is small enough to download once over mobile data.
 */
object Catalog {
    private const val APACHE = "Apache 2.0"
    private const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

    /** For phones with 6 GB of memory or more. */
    val standard = CatalogModel(
        id = "qwen3.5-2b",
        name = "Qwen 3.5 2B",
        repo = "unsloth/Qwen3.5-2B-GGUF",
        revision = "f6d5376be1edb4d416d56da11e5397a961aca8ae",
        fileName = "Qwen3.5-2B-Q4_K_M.gguf",
        sizeBytes = 1_280_835_840,
        sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
        license = APACHE,
        licenseUrl = APACHE_URL,
    )

    /** For phones with less memory: same family, a third of the size. */
    val compact = CatalogModel(
        id = "qwen3.5-0.8b",
        name = "Qwen 3.5 0.8B",
        repo = "unsloth/Qwen3.5-0.8B-GGUF",
        revision = "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0",
        fileName = "Qwen3.5-0.8B-Q4_K_M.gguf",
        sizeBytes = 532_517_120,
        sha256 = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
        license = APACHE,
        licenseUrl = APACHE_URL,
    )

    fun forPhone(ramGb: Int): CatalogModel = if (ramGb >= 6) standard else compact
}
