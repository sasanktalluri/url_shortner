package com.schwab.urlshortener.controller;

import com.schwab.urlshortener.dto.CreateUrlRequest;
import com.schwab.urlshortener.dto.CreateUrlResponse;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import com.schwab.urlshortener.service.UrlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/urls")
public class UrlController {
    private final UrlService urlService;

    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    @Operation(summary = "Create a short URL", description =
            "Generates a short code for the given URL, or claims a caller-supplied custom alias.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Short URL created"),
            @ApiResponse(responseCode = "400", description = "Malformed request body "
                    + "(e.g. missing url, or expiresAt not in the future)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Custom alias already taken",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "URL is not a valid http/https URL",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    public ResponseEntity<CreateUrlResponse> create(@Valid @RequestBody CreateUrlRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(urlService.create(request));
    }

    @Operation(summary = "Get click analytics for a short code",
            description = "Returns metadata plus click_count/last_accessed_at, whether or not "
                    + "the code is currently active.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stats returned"),
            @ApiResponse(responseCode = "404", description = "No URL exists for this short code",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{shortCode}/stats")
    public ResponseEntity<UrlStatsResponse> stats(
            @Parameter(description = "The short code to look up.", example = "UdBdLWZ")
            @PathVariable String shortCode) {
        return ResponseEntity.ok(urlService.getStats(shortCode));
    }

    @Operation(summary = "Deactivate a short code",
            description = "Redirects to a deactivated code return 404. Idempotent: deactivating "
                    + "an already-inactive code still returns 204.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deactivated"),
            @ApiResponse(responseCode = "404", description = "No URL exists for this short code",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/{shortCode}")
    public ResponseEntity<Void> deactivate(
            @Parameter(description = "The short code to deactivate.", example = "UdBdLWZ")
            @PathVariable String shortCode) {
        urlService.deactivate(shortCode);
        return ResponseEntity.noContent().build();
    }
}
