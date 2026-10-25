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
 * many languages (Indian ones included). This build is tvall43's Heretic run of it: the weights are
 * edited so the model answers instead of refusing, which is why it is named as decensored. It keeps
 * the same architecture, context and speed as the original, and stays under Apache 2.0.
 */
object Catalog {
    val model = CatalogModel(
        id = "qwen3.5-2b-heretic",
        name = "Qwen 3.5 2B, decensored",
        repo = "tvall43/Qwen3.5-2B-heretic-gguf",
        revision = "b49fa6be881f769afc9c09402b78808fe8715a95",
        fileName = "Qwen3.5-2B-heretic-Q4_K_M.gguf",
        sizeBytes = 1_270_808_064,
        sha256 = "e9c7c564f4a7e58da42e37377279ea1435b080b8297dc523f0626760c4cd0fa0",
        license = "Apache 2.0",
        licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
    )
}
