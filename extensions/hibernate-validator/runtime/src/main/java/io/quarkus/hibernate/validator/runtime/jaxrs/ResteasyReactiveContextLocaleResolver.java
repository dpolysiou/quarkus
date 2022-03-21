package io.quarkus.hibernate.validator.runtime.jaxrs;

import javax.enterprise.context.RequestScoped;
import javax.inject.Singleton;
import javax.ws.rs.core.HttpHeaders;

import io.quarkus.arc.Arc;
import io.quarkus.arc.DefaultBean;

@Singleton
@DefaultBean
public class ResteasyReactiveContextLocaleResolver extends AbstractLocaleResolver {

    private final HttpHeaders headers;

    // automatically injected for RESTEasy Reactive because of org.jboss.resteasy.reactive.server.injection.ContextProducers
    public ResteasyReactiveContextLocaleResolver(HttpHeaders headers) {
        System.out.println("INSTANTIATING ResteasyReactiveContextLocaleResolver");
        this.headers = headers;
    }

    @Override
    protected HttpHeaders getHeaders() {
        if (Arc.container().getActiveContext(RequestScoped.class) != null) { // only try to obtain headers if there is a request scope
            try {
                headers.getLength(); // this forces the creation of the actual object which will fail if there is no request in flight
                System.out.println(
                        "ResteasyReactiveContextLocaleResolver: accept-language = "
                                + headers.getHeaderString("Accept-Language"));
                return headers;
            } catch (IllegalStateException ignored) {
                System.out.println("ISE happened");
            }
        }
        System.out.println("accept-language is unknown");
        return null;
    }
}
