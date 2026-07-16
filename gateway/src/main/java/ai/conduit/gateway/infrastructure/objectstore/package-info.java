/**
 * Reserved ObjectStore port package.
 *
 * <p>Intentionally empty today. It exists so the ArchUnit object-store seam rule
 * ({@code ArchitectureRulesTest.object_store_access_confined_to_seam}) can name it now:
 * the AWS S3 SDK may be depended on only from {@code ..infrastructure.audit..} or this
 * package. When the claim-check / MinIO-spill story lands its object-store port here, it
 * inherits the seam without touching the rule (see F5 spec §3a-2 and §8).
 */
package ai.conduit.gateway.infrastructure.objectstore;
