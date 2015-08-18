	Dynamic resources are meant to emulate the upstream / downstream relation
between projects. While this mechanism offers somewhat less flexibility for
overall job configuration, it should allow the user to safely run jobs that
require artifacts from an upstream job at the same time as that "upstream" job.
The dynamic resources mechanism is meant to prevent equivalent configurations
in both jobs from running at the same time.

	Use case example:
	If you have a job "A" that creates some resources "R" that will be used by
another job "B", then job "A" will be upstream of job "B". If the jobs can run
on a machine independently or if they should only run a limited number of
instances at a time, waiting for job "A" to completely finish (each matrix
configuration finishes evaluating) before being able to run any configuration
for job "B" would be time wasting.

	In that case, having job "A" creating dynamic resources, and job "B"
consuming them would likely speed up the overall process. As such, whenever job
"A" finishes the build for one of its matrix configurations, it will create a
dynamic resource based on the matrix configuration itself, the name of job "B"
(the dynamic resource is reserved for "B"), and an unique token.
	Builds for any matrix configuration of job "B" will not be able to run if a
dynamic resource that matches its configuration, has an identical token,
and is reserved for the job, does not exist.

	This functionality assumes that the resources "R" created by a build
associated with one of the matrix configurations of job "A" will be used by a
build associated with a similar matrix configuration of job "B". "Similar" means
that the matrix configurations are identical, or can become identical by removing
one or more of the axis in the matrix configurations of either job.
	The resource creation can be 1:N (one matrix configuration can create any
number of dynamic resources for other jobs), but consumption always requires one
dynamic resource that has the required configuration.