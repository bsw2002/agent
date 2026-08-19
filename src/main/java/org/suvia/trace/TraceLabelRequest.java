package org.suvia.trace;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

public record TraceLabelRequest(
        @DecimalMin("1.0") @DecimalMax("5.0") double score,
        @Size(max = 2000) String reason
) {
}
