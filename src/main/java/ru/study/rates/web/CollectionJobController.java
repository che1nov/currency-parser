package ru.study.rates.web;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.study.rates.service.CollectionJobService;
import ru.study.rates.service.RateCollectionService;

@RestController
public class CollectionJobController {
    private final CollectionJobService jobs;
    private final RateCollectionService collector;

    public CollectionJobController(CollectionJobService jobs, RateCollectionService collector) {
        this.jobs = jobs;
        this.collector = collector;
    }

    @PostMapping("/api/jobs")
    public ResponseEntity<JobResponse> start(@RequestBody StartRequest request) {
        var snapshot = jobs.start(request.date() == null ? collector.today() : request.date(),
                request.delayMs() == null ? 0 : request.delayMs());
        return ResponseEntity.accepted().location(URI.create("/api/jobs/" + snapshot.id()))
                .cacheControl(CacheControl.noStore()).body(JobResponse.from(snapshot));
    }

    @GetMapping("/api/jobs/{id}")
    public ResponseEntity<JobResponse> status(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(JobResponse.from(jobs.find(id)));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail missing(NoSuchElementException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(RejectedExecutionException.class)
    ResponseEntity<ProblemDetail> busy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Очередь заданий заполнена"));
    }

    public record StartRequest(LocalDate date, Integer delayMs) { }
    public record JobResponse(UUID id, String state, LocalDate date, Instant startedAt, Instant completedAt,
                              List<RateController.RateResponse> rates, String error) {
        static JobResponse from(CollectionJobService.Snapshot snapshot) {
            return new JobResponse(snapshot.id(), snapshot.state(), snapshot.date(), snapshot.startedAt(),
                    snapshot.completedAt(), snapshot.rates().stream().map(RateController.RateResponse::from).toList(),
                    snapshot.error());
        }
    }
}
