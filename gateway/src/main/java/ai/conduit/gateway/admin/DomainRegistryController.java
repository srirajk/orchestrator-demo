package ai.conduit.gateway.admin;

import ai.conduit.gateway.domain.manifest.DomainManifest;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.domain.manifest.SubDomainManifest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/domains")
public class DomainRegistryController {

    private final DomainManifestStore store;

    public DomainRegistryController(DomainManifestStore store) {
        this.store = store;
    }

    @GetMapping
    public ResponseEntity<Map<String, DomainManifest>> listDomains() {
        return ResponseEntity.ok(store.allDomains());
    }

    @GetMapping("/{domainId}")
    public ResponseEntity<DomainManifest> getDomain(@PathVariable String domainId) {
        return store.getDomain(domainId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{domainId}/subdomains")
    public ResponseEntity<List<SubDomainManifest>> getSubDomains(@PathVariable String domainId) {
        return ResponseEntity.ok(store.getSubDomainsForDomain(domainId));
    }

    @GetMapping("/{domainId}/subdomains/{subDomainId}")
    public ResponseEntity<SubDomainManifest> getSubDomain(
            @PathVariable String domainId, @PathVariable String subDomainId) {
        return store.getSubDomain(subDomainId)
            .filter(sd -> domainId.equals(sd.parentDomain()))
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
