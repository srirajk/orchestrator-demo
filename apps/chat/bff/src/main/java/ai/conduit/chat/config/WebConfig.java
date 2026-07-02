package ai.conduit.chat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.core.io.Resource;

import java.io.IOException;

/**
 * Serves the built React SPA as static resources with a history-API fallback:
 * any non-{@code /api}, non-asset path resolves to {@code index.html} so client-side
 * routing (e.g. {@code /c/{id}}) works on hard refresh.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaPathResourceResolver());
    }

    /**
     * Resolves static assets normally; for unmatched, non-API paths it falls back to
     * the SPA shell ({@code index.html}). API and actuator paths are intentionally not
     * handled here so they hit their controllers / return 404 as appropriate.
     */
    static final class SpaPathResourceResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }
            if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
                return null;
            }
            Resource index = location.createRelative("index.html");
            return (index.exists() && index.isReadable()) ? index : null;
        }
    }
}
