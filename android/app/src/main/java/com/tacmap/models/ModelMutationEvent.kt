package com.tacmap.models

/** Lossless store mutation signal used by the global sync revision journal. */
enum class ModelMutationOrigin { LOCAL, REMOTE_SYNC }

data class ModelMutationEvent(
    val localIds: Set<String>,
    val origin: ModelMutationOrigin = ModelMutationOrigin.LOCAL,
)
