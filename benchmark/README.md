## NuProcess JMH Benchmark

Prior to excuting the benchmark, make sure that the primary (NuProcess) library has been built and/or installed.
```cli
mvn clean install
```

Simple execution of the benchmark is as follows:
```cli
cd ./benchmark
./benchmark.sh quick
```

The following command line options are available when running the ``benchmark.sh`` script:
```cli
benchmark.sh [clean] [-t <threads>] [quick|medium|long|profile|debug]
```

| Option  | Description                                               |
|:-------:|:--------------------------------------------------------- |
| clean   | recompile the benchmark |
| -t &lt;threads> | execute JMH with the specified number of threads |
| quick   | a "quick" run of the benchmark; fewer iterations and forks |
| medium  | a "medium" run of the benchmark; more iterations and forks |
| long    | a "long" run of the benchmark; maximum iterations and forks |
| profile | start JMH such as to allow attaching JProfiler (MacOS) |
| debug   | start JMH such as to allow the attachment of a debugger |
