This is a Clojure program designed to realistically replay HTTP GET
requests from a log file to the server of your choice.  By
"realistically", I mean it does its best to ensure that requests are
made in the order they originally arrived, and at a similar rate.

Why another load testing tool?  I had trouble finding something that
could take an existing log file and generate similar traffic patterns
based off its timestamps, and I wanted something that didn't rely on a
whole bunch of threads for concurrency (this tool uses NIO to fire
many requests from a single thread).


## Building it

The usual steps:

  1.  Get Leiningen from http://github.com/technomancy/leiningen and put
      the 'lein' script somewhere in your $PATH.

  2.  Run 'lein uberjar'.  Lein will grab
      all required dependencies and produce a 'traffic-replayer-[VERSION].jar'.



## Using it

Using this program is a two step process: you generate a script file
(containing URLs requested and timing information), and then you
replay it at a specified host.


### Generating a script file

You could do this yourself using Perl/sed/awk/whatever, but this
program does know how to generate script files from HAProxy logs.  The
script file format is just:

  > [ms since epoch]\t[requested URI (e.g. /foo)]

Lines must be sorted numerically by the timestamp (e.g. sort -n -k1).

To generate a script file from a HAProxy log, I used:

    $ grep 'GET /some/url' ~/some.log.2010-07-19 | \
        java -jar traffic-replayer-[VERSION].jar generate /dev/stdin script.out

HaProxy logs work well because they log the time the request was
received, as well as the time it completed (many servers only log the
latter, which is less useful for this sort of thing).


### Replaying a script file

Replay a script file is similar:

    # To replay traffic against http://localhost/someprefix
    #
    $ java -jar traffic-replayer-[VERSION].jar replay script.out http://localhost/someprefix run.log

On the console you'll see timing information ("sleeping NNN ms before
next request").  In your run.log you'll see each URL that has been
requested and how long it took to come back.
