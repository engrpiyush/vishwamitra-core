package ai.vishwakarma.labelling.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync

/** Enables `@Async` so import-dataset validation runs off the request thread. */
@Configuration @EnableAsync class AsyncConfig
