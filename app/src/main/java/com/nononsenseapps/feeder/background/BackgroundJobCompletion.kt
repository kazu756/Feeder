package com.nononsenseapps.feeder.background

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentMap

/** Register before starting a lazy job, including an already-cancelled service scope. */
internal fun trackBackgroundJob(
    jobs: ConcurrentMap<Int, Job>,
    id: Int,
    job: Job,
    finished: () -> Unit,
) {
    jobs[id] = job
    job.invokeOnCompletion {
        try {
            finished()
        } finally {
            jobs.remove(id, job)
        }
    }
}
