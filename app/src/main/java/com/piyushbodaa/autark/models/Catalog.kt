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
    /** Pinned to a commit so the size and checksum below always match what gets downloaded. */
    val url get() = "https://huggingface.co/$repo/resolve/$revision/$fileName"
}

/**
 * One model for every phone; people never have to choose. Qwen 3.5 is Apache-licensed and speaks
 * many languages (Indian ones included).
 */
object Catalog {
    val model = CatalogModel(
        id = "qwen3.5-2b",
        name = "Qwen 3.5 2B",
        repo = "unsloth/Qwen3.5-2B-GGUF",
        revision = "f6d5376be1edb4d416d56da11e5397a961aca8ae",
        fileName = "Qwen3.5-2B-Q4_K_M.gguf",
        sizeBytes = 1_280_835_840,
        sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
        license = "Apache 2.0",
        licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
    )
}
