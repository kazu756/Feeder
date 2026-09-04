package com.nononsenseapps.feeder.localtranslation

import com.nononsenseapps.feeder.util.FilePathProvider
import com.nononsenseapps.feeder.util.logDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream

class BergamotModelManager(
    override val di: DI,
    private val registryUrl: String = DEFAULT_REGISTRY_URL,
) : DIAware {
    private val filePathProvider: FilePathProvider by instance()
    private val okHttpClient: OkHttpClient by instance()
    private val modelRoot: File = filePathProvider.filesDir.resolve("translation-models").resolve("bergamot")
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            prettyPrint = false
        }
    private val registryFile: File = modelRoot.resolve("registry.json")
    private val downloadMutex = Mutex()
    private val _downloadProgress = MutableStateFlow<BergamotModelDownloadProgress?>(null)
    val downloadProgress: StateFlow<BergamotModelDownloadProgress?> = _downloadProgress.asStateFlow()

    suspend fun prepare(
        sourceLanguage: String,
        targetLanguage: String,
    ): BergamotModelPreparation =
        withContext(Dispatchers.IO) {
            downloadMutex.withLock {
                val source = normalizeLanguageCode(sourceLanguage)
                val target = normalizeLanguageCode(targetLanguage)
                try {
                    val registry =
                        loadRegistry(
                            sourceLanguage = source,
                            targetLanguage = target,
                        )
                            ?: return@withLock BergamotModelPreparation.Error(
                                reason = BergamotModelPreparation.ErrorReason.RegistryMissing,
                                sourceLanguage = source,
                                targetLanguage = target,
                            )

                    val path =
                        resolvePath(registry, source, target)
                            ?: return@withLock BergamotModelPreparation.Error(
                                reason = BergamotModelPreparation.ErrorReason.NoAppModel,
                                sourceLanguage = source,
                                targetLanguage = target,
                            )

                    logDebug(LOG_TAG, "Resolved Bergamot model path $source->$target: ${path.joinToString { "${it.from}->${it.to}" }}")

                    val downloadState = ModelDownloadState.forPath(path)
                    val entries = mutableListOf<BergamotModelRegistryEntry>()
                    for (entry in path) {
                        val downloaded =
                            ensureDownloaded(
                                entry = entry,
                                downloadState = downloadState,
                            ) ?: return@withLock BergamotModelPreparation.Error(
                                reason = BergamotModelPreparation.ErrorReason.DownloadFailed,
                                sourceLanguage = entry.from,
                                targetLanguage = entry.to,
                            )
                        entries += downloaded
                    }

                    BergamotModelPreparation.Ready(entries)
                } finally {
                    _downloadProgress.value = null
                }
            }
        }

    suspend fun languagePairStatus(
        sourceLanguage: String,
        targetLanguage: String,
        allowNetwork: Boolean = true,
    ): BergamotLanguagePairStatus =
        withContext(Dispatchers.IO) {
            val source = normalizeLanguageCode(sourceLanguage)
            val target = normalizeLanguageCode(targetLanguage)
            val registry = loadRegistry(allowNetwork = allowNetwork) ?: return@withContext BergamotLanguagePairStatus.RegistryMissing
            val path = resolvePath(registry, source, target) ?: return@withContext BergamotLanguagePairStatus.Unavailable
            if (path.all(::isDownloaded)) {
                BergamotLanguagePairStatus.Downloaded
            } else {
                BergamotLanguagePairStatus.AvailableToDownload
            }
        }

    suspend fun deleteLanguagePair(
        sourceLanguage: String,
        targetLanguage: String,
    ) {
        withContext(Dispatchers.IO) {
            val source = normalizeLanguageCode(sourceLanguage)
            val target = normalizeLanguageCode(targetLanguage)
            val registry = loadRegistry(allowNetwork = false) ?: return@withContext
            resolvePath(registry, source, target)
                ?.forEach { entry -> pairDir(entry.from, entry.to).deleteRecursively() }
        }
    }

    fun storageLocation(): File = modelRoot

    suspend fun getDownloadedLanguagePairs(): List<LanguagePairInfo> =
        withContext(Dispatchers.IO) {
            val registry = loadRegistry(allowNetwork = false) ?: return@withContext emptyList()
            registry
                .filter { entry ->
                    val dir = pairDir(entry.from, entry.to)
                    dir.isDirectory &&
                        entry.files.values.all { file ->
                            dir.resolve(file.name.sanitizedModelFileName()).isFile
                        }
                }.map { entry ->
                    val dir = pairDir(entry.from, entry.to)
                    val totalSize =
                        if (dir.isDirectory) {
                            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        } else {
                            0L
                        }
                    LanguagePairInfo(
                        sourceLanguage = entry.from,
                        targetLanguage = entry.to,
                        sizeBytes = totalSize,
                    )
                }
        }

    fun getRegistryEntries(): List<BergamotModelRegistryEntry> = loadRegistry(allowNetwork = false).orEmpty()

    private fun loadRegistry(
        allowNetwork: Boolean = true,
        sourceLanguage: String? = null,
        targetLanguage: String? = null,
    ): List<BergamotModelRegistryEntry>? {
        registryFile
            .takeIf(File::isFile)
            ?.readText()
            ?.let(::parseRegistry)
            ?.let { return it }

        if (allowNetwork) {
            if (sourceLanguage != null && targetLanguage != null) {
                _downloadProgress.value =
                    BergamotModelDownloadProgress(
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        fileName = REGISTRY_FILE_NAME,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                    )
            }
            fetchRegistry()?.let { return it }
        }

        return null
    }

    private fun fetchRegistry(): List<BergamotModelRegistryEntry>? =
        runCatching {
            val request = Request.Builder().url(registryUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching null
                }
                response.body.string().also { body ->
                    registryFile.parentFile?.mkdirs()
                    registryFile.writeText(body)
                }
            }
        }.getOrNull()?.let(::parseRegistry)

    private fun parseRegistry(content: String): List<BergamotModelRegistryEntry>? =
        runCatching {
            val registry =
                json
                    .parseToJsonElement(content)
                    .jsonObject
                    .mapNotNull { (key, value) ->
                        val normalizedKey = key.lowercase(Locale.ROOT)
                        if (normalizedKey.length < 4) {
                            return@mapNotNull null
                        }
                        val from = normalizedKey.substring(0, 2)
                        val to = normalizedKey.substring(2, 4)
                        val files = parseModelFiles(value.jsonObject, from, to)
                        BergamotModelRegistryEntry(
                            from = from,
                            to = to,
                            files = files,
                        )
                    }
            registry + MOZILLA_JAPANESE_MODELS.filterNot { bundled -> registry.any { it.from == bundled.from && it.to == bundled.to } }
        }.getOrNull()

    private fun parseModelFiles(
        files: JsonObject,
        from: String,
        to: String,
    ): Map<String, BergamotModelFile> =
        files
            .mapNotNull { (part, value) ->
                val fileObject = value as? JsonObject ?: return@mapNotNull null
                val name =
                    fileObject["name"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: return@mapNotNull null
                val remoteUrl =
                    if (name.startsWith("http://", ignoreCase = true) || name.startsWith("https://", ignoreCase = true)) {
                        name
                    } else {
                        "$DEFAULT_MODEL_BASE_URL/$from$to/$name"
                    }
                part to
                    BergamotModelFile(
                        name = name.substringAfterLast('/'),
                        remoteUrl = remoteUrl,
                        size = fileObject["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                        expectedSha256Hash = fileObject["expectedSha256Hash"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        config = fileObject["config"],
                        gzipCompressed = fileObject["gzipCompressed"]?.jsonPrimitive?.contentOrNull.toBoolean(),
                    )
            }.toMap()

    private fun resolvePath(
        registry: List<BergamotModelRegistryEntry>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<BergamotModelRegistryEntry>? {
        registry.firstOrNull { it.from == sourceLanguage && it.to == targetLanguage }?.let { return listOf(it) }

        if (sourceLanguage == PIVOT_LANGUAGE || targetLanguage == PIVOT_LANGUAGE) {
            return null
        }

        val outbound = registry.firstOrNull { it.from == sourceLanguage && it.to == PIVOT_LANGUAGE }
        val inbound = registry.firstOrNull { it.from == PIVOT_LANGUAGE && it.to == targetLanguage }
        return if (outbound != null && inbound != null) {
            listOf(outbound, inbound)
        } else {
            null
        }
    }

    private fun ensureDownloaded(
        entry: BergamotModelRegistryEntry,
        downloadState: ModelDownloadState,
    ): BergamotModelRegistryEntry? {
        val downloadedFiles = mutableMapOf<String, BergamotModelFile>()
        val dir = pairDir(entry.from, entry.to)
        dir.mkdirs()

        entry.files.forEach { (part, file) ->
            if (file.expectedSha256Hash.isBlank()) {
                return null
            }
            val localFile = dir.resolve(file.name.sanitizedModelFileName())
            val ready =
                if (localFile.isFile && file.matchesSha256(localFile)) {
                    downloadState.addAlreadyDownloaded(file)
                    true
                } else {
                    downloadFile(
                        modelFile = file,
                        destination = localFile,
                        sourceLanguage = entry.from,
                        targetLanguage = entry.to,
                        downloadState = downloadState,
                    )
                }
            if (!ready) {
                return null
            }
            downloadedFiles[part] = file.copy(url = "file://" + localFile.absolutePath)
        }

        val downloadedEntry = entry.copy(files = downloadedFiles)
        dir.resolve("model.json").writeText(json.encodeToString(downloadedEntry))
        return downloadedEntry
    }

    private fun downloadFile(
        modelFile: BergamotModelFile,
        destination: File,
        sourceLanguage: String,
        targetLanguage: String,
        downloadState: ModelDownloadState,
    ): Boolean =
        runCatching {
            logDebug(LOG_TAG, "Downloading Bergamot model part for $sourceLanguage->$targetLanguage: ${modelFile.name}")
            _downloadProgress.value =
                downloadState.toProgress(
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    fileName = modelFile.name,
                )
            val request = Request.Builder().url(modelFile.remoteUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching false
                }
                val downloadedFile = File(destination.parentFile, "${destination.name}.download")
                val verifiedFile = File(destination.parentFile, "${destination.name}.verified")
                downloadedFile.delete()
                verifiedFile.delete()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                response.body.byteStream().use { input ->
                    downloadedFile.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            output.write(buffer, 0, read)
                            downloadState.addBytes(read.toLong())
                            _downloadProgress.value =
                                downloadState.toProgress(
                                    sourceLanguage = sourceLanguage,
                                    targetLanguage = targetLanguage,
                                    fileName = modelFile.name,
                                )
                        }
                    }
                }
                if (modelFile.gzipCompressed) {
                    GZIPInputStream(downloadedFile.inputStream()).use { input ->
                        verifiedFile.outputStream().use(input::copyTo)
                    }
                    downloadedFile.delete()
                } else {
                    moveDownloadedFile(downloadedFile, verifiedFile)
                }
                if (!modelFile.matchesSha256(verifiedFile)) {
                    verifiedFile.delete()
                    return@runCatching false
                }
                destination.parentFile?.mkdirs()
                moveDownloadedFile(verifiedFile, destination).also { moved ->
                    logDebug(LOG_TAG, "Bergamot model part ready for $sourceLanguage->$targetLanguage: ${modelFile.name}, success=$moved")
                }
            }
        }.onFailure { error ->
            logDebug(LOG_TAG, "Bergamot model download failed for $sourceLanguage->$targetLanguage: ${error::class.simpleName}")
        }.getOrDefault(false)

    private fun isDownloaded(entry: BergamotModelRegistryEntry): Boolean =
        entry.files.values.all { file ->
            val localFile = pairDir(entry.from, entry.to).resolve(file.name.sanitizedModelFileName())
            localFile.isFile && file.matchesSha256(localFile)
        }

    fun pairDir(
        from: String,
        to: String,
    ): File = modelRoot.resolve("$from-$to")

    private fun moveDownloadedFile(
        tempFile: File,
        destination: File,
    ): Boolean =
        runCatching {
            Files.move(
                tempFile.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        }.recoverCatching {
            Files.move(
                tempFile.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.getOrElse {
            tempFile.delete()
            false
        }

    private fun BergamotModelFile.matchesSha256(file: File): Boolean = expectedSha256Hash.isNotBlank() && sha256(file).equals(expectedSha256Hash, ignoreCase = true)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun String.sanitizedModelFileName(): String =
        substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .ifBlank { "model-file" }

    companion object {
        private const val DEFAULT_REGISTRY_URL = "https://bergamot.s3.amazonaws.com/models/index.json"
        private const val DEFAULT_MODEL_BASE_URL = "https://bergamot.s3.amazonaws.com/models"
        private const val PIVOT_LANGUAGE = "en"
        private const val REGISTRY_FILE_NAME = "registry.json"
        private const val LOG_TAG = "FEEDER_BERGAMOT_MODEL"

        private const val MOZILLA_MODEL_BASE_URL =
            "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data"

        private val MOZILLA_JAPANESE_MODELS =
            listOf(
                BergamotModelRegistryEntry(
                    from = "en",
                    to = "ja",
                    files =
                        mapOf(
                            "lex" to
                                mozillaJapaneseModelFile(
                                    path = "models/en-ja/llmaat_finetune10M_qe8_f2_ApiGGQIwTKuF9i_k3n9Q2Q/exported/lex.50.50.enja.s2t.bin.gz",
                                    size = 2_341_409L,
                                    sha256 = "edfb7eb47b98a2689b804ab3614c31b24f1257aa54b1154da3d85e1ae8152d9f",
                                ),
                            "model" to
                                mozillaJapaneseModelFile(
                                    path = "models/en-ja/llmaat_finetune10M_qe8_f2_ApiGGQIwTKuF9i_k3n9Q2Q/exported/model.enja.intgemm.alphas.bin.gz",
                                    size = 33_052_218L,
                                    sha256 = "59ae659f9bb63e4f81f474fe3c03d3f4499434b5f9e779fab7c12a45f31fd562",
                                ),
                            "srcvocab" to
                                mozillaJapaneseModelFile(
                                    path = "models/en-ja/llmaat_finetune10M_qe8_f2_ApiGGQIwTKuF9i_k3n9Q2Q/exported/srcvocab.enja.spm.gz",
                                    size = 404_352L,
                                    sha256 = "970c98d174fc01e0339fbabbf45af36a4be3f26f819ec1a5ea1189f71e091889",
                                ),
                            "trgvocab" to
                                mozillaJapaneseModelFile(
                                    path = "models/en-ja/llmaat_finetune10M_qe8_f2_ApiGGQIwTKuF9i_k3n9Q2Q/exported/trgvocab.enja.spm.gz",
                                    size = 431_278L,
                                    sha256 = "3b3d9f8f3a034d98d0a476f1794fa79c01e4e98a967ceb6777a66ba2d03ec1e1",
                                ),
                        ),
                ),
                BergamotModelRegistryEntry(
                    from = "ja",
                    to = "en",
                    files =
                        mapOf(
                            "lex" to
                                mozillaJapaneseModelFile(
                                    path = "models/ja-en/cjk_retrain_base-memory_NLRJLD_pQFyrvgKtbie2nA/exported/lex.50.50.jaen.s2t.bin.gz",
                                    size = 4_819_610L,
                                    sha256 = "525f412f0d210536c2933c78ae395fa0bf2b5ee6cc5dda61ebc2e79410ebaee4",
                                ),
                            "model" to
                                mozillaJapaneseModelFile(
                                    path = "models/ja-en/cjk_retrain_base-memory_NLRJLD_pQFyrvgKtbie2nA/exported/model.jaen.intgemm.alphas.bin.gz",
                                    size = 32_577_435L,
                                    sha256 = "3a603e20bfe1be86071913f9e23ab5129075bc0a8490151020ac4821e4f17302",
                                ),
                            "vocab" to
                                mozillaJapaneseModelFile(
                                    path = "models/ja-en/cjk_retrain_base-memory_NLRJLD_pQFyrvgKtbie2nA/exported/vocab.jaen.spm.gz",
                                    size = 746_616L,
                                    sha256 = "5cb217758bae05877bb3f0c2f612e4e7c1e4cb03c10db11f4a47098d7ae62919",
                                ),
                        ),
                ),
            )

        private fun mozillaJapaneseModelFile(
            path: String,
            size: Long,
            sha256: String,
        ): BergamotModelFile =
            BergamotModelFile(
                name = path.substringAfterLast('/').removeSuffix(".gz"),
                remoteUrl = "$MOZILLA_MODEL_BASE_URL/$path",
                size = size,
                expectedSha256Hash = sha256,
                gzipCompressed = true,
            )
    }
}

data class BergamotModelDownloadProgress(
    val sourceLanguage: String,
    val targetLanguage: String,
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val isIndeterminate: Boolean
        get() = totalBytes <= 0L

    val fraction: Float
        get() =
            when {
                totalBytes <= 0L -> 0f
                else -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            }
}

private data class ModelDownloadState(
    val totalBytes: Long,
    var downloadedBytes: Long = 0L,
) {
    fun addAlreadyDownloaded(file: BergamotModelFile) {
        downloadedBytes += file.size.coerceAtLeast(0L)
    }

    fun addBytes(bytes: Long) {
        downloadedBytes += bytes.coerceAtLeast(0L)
    }

    fun toProgress(
        sourceLanguage: String,
        targetLanguage: String,
        fileName: String,
    ): BergamotModelDownloadProgress =
        BergamotModelDownloadProgress(
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            fileName = fileName,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
        )

    companion object {
        fun forPath(path: List<BergamotModelRegistryEntry>): ModelDownloadState =
            ModelDownloadState(
                totalBytes =
                    path
                        .flatMap { it.files.values }
                        .sumOf { it.size.coerceAtLeast(0L) },
            )
    }
}

sealed interface BergamotModelPreparation {
    data class Ready(
        val modelRegistry: List<BergamotModelRegistryEntry>,
    ) : BergamotModelPreparation

    data class Error(
        val reason: ErrorReason,
        val sourceLanguage: String,
        val targetLanguage: String,
    ) : BergamotModelPreparation

    enum class ErrorReason {
        RegistryMissing,
        NoAppModel,
        DownloadFailed,
    }
}

enum class BergamotLanguagePairStatus {
    RegistryMissing,
    Unavailable,
    AvailableToDownload,
    Downloaded,
}

@Serializable
data class BergamotModelRegistryEntry(
    val from: String,
    val to: String,
    val files: Map<String, BergamotModelFile>,
)

@Serializable
data class BergamotModelFile(
    val name: String,
    val remoteUrl: String,
    val size: Long,
    val expectedSha256Hash: String = "",
    val url: String? = null,
    val config: JsonElement? = null,
    val gzipCompressed: Boolean = false,
)

data class LanguagePairInfo(
    val sourceLanguage: String,
    val targetLanguage: String,
    val sizeBytes: Long,
)
