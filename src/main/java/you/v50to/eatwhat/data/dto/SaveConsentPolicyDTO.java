package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.*;

/** Compare-and-set edit; revision comes from the administrator read endpoint. */
public record SaveConsentPolicyDTO(
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String version,
        @NotBlank @Size(max=2048) String url,
        @NotNull @Min(0) @Max(9007199254740990L) Long expectedRevision) { }
