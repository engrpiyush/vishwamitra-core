package ai.vishwakarma.labelling.service

import ai.vishwakarma.labelling.domain.BaseModel
import ai.vishwakarma.labelling.domain.ModelVersion

/** Builds the off-GCP serving handoff (download + vLLM) for a ready model version. */
object ServeCommand {

    fun build(version: ModelVersion, registry: BaseModel? = null): String {
        val ckpt =
            version.gcsCheckpointUri ?: return "# Checkpoint not available yet (version not READY)."
        val served = "${version.family}-${version.version}"
        // VA-86: the family's registry row states its accelerator needs; generic advice otherwise.
        val gpu =
            registry?.acceleratorSpec?.takeIf { it.isNotBlank() }
                ?: "a 48–80 GB GPU; ~24 GB at 4-bit"
        return """
            # 1. Download the merged-weights checkpoint to a GPU box:
            gcloud storage cp -r "$ckpt" ./$served

            # 2. Serve with vLLM (needs $gpu):
            vllm serve ./$served \
              --served-model-name $served \
              --max-model-len 8192

            # 3. Query, then tear the box down (no persistent serving on GCP).
        """
            .trimIndent()
    }
}
