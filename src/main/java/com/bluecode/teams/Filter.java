package com.bluecode.teams;

import java.util.Optional;

public record Filter(Optional<Status> status) {
    public Filter {
        status = status == null ? Optional.empty() : status;
    }
}
