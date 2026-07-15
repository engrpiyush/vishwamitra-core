package ai.vishwakarma.labelling

import ai.vishwakarma.labelling.config.AppProperties
import ai.vishwakarma.labelling.domain.StageConfigDoc
import ai.vishwakarma.labelling.persistence.StageConfigRepository
import ai.vishwakarma.labelling.service.StageConfigService
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * A [StageConfigService] with no stored overrides: resolves exactly the given bootstrap [props],
 * making the VA-83 live-config seam transparent in unit tests.
 */
fun liveConfig(props: AppProperties = AppProperties()): StageConfigService {
    val repo = mock(StageConfigRepository::class.java)
    `when`(repo.find(anyString())).thenAnswer { StageConfigDoc(stage = it.arguments[0] as String) }
    return StageConfigService(props, repo)
}
