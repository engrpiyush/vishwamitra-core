package ai.vishwakarma.labelling.config

import ai.vishwakarma.labelling.security.TermsGateInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** MVC wiring for the subject world (VA-30): the terms gate rides only the internal /s paths. */
@Configuration
class WebConfig(private val termsGate: TermsGateInterceptor) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(termsGate).addPathPatterns("/s", "/s/**")
    }
}
