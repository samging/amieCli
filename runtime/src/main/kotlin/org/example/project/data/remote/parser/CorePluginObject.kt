package org.example.project.data.remote.parser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CorePluginObject(
    val name: String,
    val path: String,
    val metadata: CorePluginMetadata
)

@Serializable
data class CorePluginMetadata(
    @SerialName("file_size_bytes")
    val fileSizeBytes: Long,
    val encoding: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("last_modified_by")
    val lastModifiedBy: String,
    val tags: List<String>,
    val checksum: String
)
