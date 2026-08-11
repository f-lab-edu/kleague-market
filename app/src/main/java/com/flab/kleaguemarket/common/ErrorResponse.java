package com.flab.kleaguemarket.common;

import java.time.Instant;

public record ErrorResponse(String code, String message, Instant timestamp, String path) {
}
