### Design

nREPL largely consists of three abstractions: handlers, middleware, and
transports.  These are roughly analogous to the handlers, middleware, and
adapters of [Ring](https://github.com/ring-clojure/ring), though there are some
important semantic differences. Finally, nREPL is fundamentally message-oriented
and asynchronous (in contrast to most REPLs that build on top of streams
provided by e.g.  terminals).

#### Messages

nREPL messages are maps.  The keys and values that may be included in messages
depends upon the transport being used; different transports may encode messages
differently, and therefore may or may not be able to represent certain data
types.

##### Requests

Each message sent to an nREPL endpoint constitutes a "request" to perform a
particular operation, which is indicated by a `"op"` entry.  Each operation may
further require the incoming message to contain other data.  Which data an
operation requires or may accept varies; for example, a message to evaluate
some code might look like this:

```clojure
{"op" "eval" "code" "(+ 1 2 3)"}
```

The result(s) of performing each operation may be sent back to the nREPL client
in one or more response messages, the contents of which again depend upon the
operation.

##### Responses

The server may produce multiple messages in response to each client message (request).
The structure of the response is unique per each message type, but there are a few
fundamental properties that will always be around in the responses:

- `id` The ID of the request for which the response was generated.
- `session` The ID of the session for which the response was generated.
- `status` The status of the response. Here there would either be something like "done"
if a request has been fully processed or the reason for a failure (e.g. "namespace-not-found"). Not every
response message would have the status key. If some request generated multiple response messages only the
final one would have the status attached to it.

As mentioned earlier each op would produce different response messages. Here's what you can expect
to see in responses generated as a result of an `eval` op invocation.

- `ns` The stringified value of `*ns*` at the time of the response message's
  generation.
- `out` Contains content written to `*out*` while the request's code was being evaluated.  Messages containing `*out*` content may be sent at the discretion
of the server, though at minimum corresponding with flushes of the underlying
stream/writer.
- `err` Same as `out`, but for `*err*`.
- `value` The result of printing a result of evaluating a form in the code sent
  in the corresponding request.  More than one value may be sent, if more than
one form can be read from the request's code string.  In contrast to the output
written to `*out*` and `*err*`, this may be usefully/reliably read and utilized
by the client, e.g. in tooling contexts, assuming the evaluated code returns a
printable and readable value.  Interactive clients will likely want to simply
stream `value`'s content to their UI's primary output / log.

Note that evaluations that are interrupted may nevertheless result
in multiple response messages being sent prior to the interrupt
occurring.

!!! Tip

    You're favourite editor/nREPL client might have some utility to
    monitor the exchange of messages between the client and nREPL
    (e.g. CIDER has a `*nrepl-messages*` where you can monitor all
    requests and responses). That's a great way to get a better understanding
    of nREPL server responses.

<!--

Note: Seems that's some section from the nREPL 0.1 era, as 0.2+ doesn't have
this timeout behaviour. (@bbatsov)

### Timeouts and Interrupts

Each message has a timeout associated with it, which controls the maximum time
that a message's code will be allowed to run before being interrupted and a
response message being sent indicating a status of `timeout`.

The processing of a message may be interrupted by a client by sending another
message containing code that invokes the `nrepl/interrupt`
function, providing it with the string ID of the message to be interrupted.
The interrupt will be responded to separately as with any other message. (The
provided client implementation provides a simple abstraction for handling
responses that makes issuing interrupts very straightforward.)

*Note that interrupts are performed on a “best-effort” basis, and are subject
to the limitations of Java’s threading model.  For more read
[here](http://download.oracle.com/javase/1.5.0/docs/api/java/lang/Thread.html#interrupt%28%29)
and
[here](http://download.oracle.com/javase/1.5.0/docs/guide/misc/threadPrimitiveDeprecation.html).*
-->

#### Transports

<!-- talk about strings vs. bytestrings, the encoding thereof, etc when we
figure that out -->

_Transports_ are roughly analogous to Ring's adapters: they provide an
implementation of a common protocol (`nrepl.transport.Transport`)
to enable nREPL clients and servers to send and receive messages without regard
for the underlying channel or particulars of message encoding.

nREPL includes two transports, both of which are socket-based: a "tty"
transport that allows one to connect to an nREPL endpoint using e.g. `telnet`
(which therefore supports only the most simplistic interactive evaluation of
expressions), and one that uses
[bencode](https://wiki.theory.org/index.php/BitTorrentSpecification#Bencoding) to encode
nREPL messages over sockets.  It is the latter that is used by default by
`nrepl.server/start-server` and `nrepl.core/connect`.

[Other nREPL transports are provided by the community](https://github.com/nrepl/nrepl/wiki/Extensions).

#### Handlers

_Handlers_ are functions that accept a single incoming message as an argument.
An nREPL server is started with a single handler function, which will be used
to process messages for the lifetime of the server.  Note that handler return
values are _ignored_; results of performing operations should be sent back to
the client via the transport in use (which will be explained shortly).  This
may seem peculiar, but is motivated by two factors:

* Many operations — including something as simple as code evaluation — is
  fundamentally asynchronous with respect to the nREPL server
* Many operations can produce multiple results (e.g. evaluating a snippet of
  code like `"(+ 1 2) (def a 6)"`).

Thus, messages provided to nREPL handlers are guaranteed to contain a
`:transport` entry containing the [transport](#transports) that should be used
to send all responses precipitated by a given message.  (This slot is added by
the nREPL server itself, thus, if a client sends any message containing a
`"transport"` entry, it will be bashed out by the `Transport` that was the
source of the message.)  Further, all messages provided to nREPL handlers have
keyword keys (as per `clojure.walk/keywordize-keys`).

Depending on its `:op`, a message might be required to contain other slots, and
might optionally contain others.  It is generally the case that request
messages should contain a globally-unique `:id`.
Every request must provoke at least one and potentially many response messages,
each of which should contain an `:id` slot echoing that of the provoking
request.

Once a handler has completely processed a message, a response
containing a `:status` of `:done` must be sent.  Some operations necessitate
that additional responses related to the processing of a request are sent after
a `:done` `:status` is reported (e.g. delivering content written to `*out*` by
evaluated code that started a `future`).
Other statuses are possible, depending upon the semantics of the `:op` being
handled; in particular, if the message is malformed or incomplete for a
particular `:op`, then a response with an `:error` `:status` should be sent,
potentially with additional information about the nature of the problem.

It is possible for an nREPL server to send messages to a client that are not a
direct response to a request (e.g. streaming content written to `System/out`
might be started/stopped by requests, but messages containing such content
can't be considered responses to those requests).

If the handler being used by an nREPL server does not recognize or cannot
perform the operation indicated by a request message's `:op`, then it should
respond with a message containing a `:status` of `"unknown-op"`.

It is currently the case that the handler provided as the `:handler` to
`nrepl.server/start-server` is generally built up as a result of
composing multiple pieces of middleware.

#### Middleware

_Middleware_ are higher-order functions that accept a handler and return a new
handler that may compose additional functionality onto or around the original.
For example, some middleware that handles a hypothetical `"time?"` `:op` by
replying with the local time on the server:

```clojure
(require '[nrepl.transport :as t])
(use '[nrepl.misc :only (response-for)])

(defn current-time
  [h]
  (fn [{:keys [op transport] :as msg}]
    (if (= "time?" op)
      (t/send transport (response-for msg :status :done :time (System/currentTimeMillis)))
      (h msg))))
```

A little silly, but this pattern should be familiar to you if you have
implemented Ring middleware before.  Nearly all of the same patterns and
expectations associated with Ring middleware should be applicable to nREPL
middleware.

All of nREPL's provided default functionality is implemented in terms of
middleware, even foundational bits like session and eval support.  This default
middleware "stack" aims to match and exceed the functionality offered by the
standard Clojure REPL, and is available at
`nrepl.server/default-middlewares`.  Concretely, it consists of a
number of middleware functions' vars that are implicitly merged with any
user-specified middleware provided to
`nrepl.server/default-handler`.  To understand how that implicit
merge works, we'll first need to talk about middleware "descriptors".

[Other nREPL middlewares are provided by the community]
(https://github.com/nrepl/nrepl/wiki/Extensions).

(See [this documentation
listing](https://github.com/nrepl/nrepl/blob/master/doc/ops.md) for
details as to the operations implemented by nREPL's default middleware stack,
what each operation expects in request messages, and what they emit for
responses.)

##### Middleware descriptors and nREPL server configuration

It is generally the case that most users of nREPL will expect some minimal REPL
functionality to always be available: evaluation (and the ability to interrupt
evaluations), sessions, file loading, and so on.  However, as with all
middleware, the order in which nREPL middleware is applied to a base handler is
significant; e.g., the session middleware's handler must look up a user's
session and add it to the message map before delegating to the handler it wraps
(so that e.g. evaluation middleware can use that session data to stand up the
user's dynamic evaluation context).  If middleware were "just" functions, then
any customization of an nREPL middleware stack would need to explicitly repeat
all of the defaults, except for the edge cases where middleware is to be
appended or prepended to the default stack.

To eliminate this tedium, the vars holding nREPL middleware functions may have
a descriptor applied to them to specify certain constraints in how that
middleware is applied.  For example, the descriptor for the
`nrepl.middleware.session/add-stdin` middleware is set thusly:

```clojure
(set-descriptor! #'add-stdin
  {:requires #{#'session}
   :expects #{"eval"}
   :handles {"stdin"
             {:doc "Add content from the value of \"stdin\" to *in* in the current session."
              :requires {"stdin" "Content to add to *in*."}
              :optional {}
              :returns {"status" "A status of \"need-input\" will be sent if a session's *in* requires content in order to satisfy an attempted read operation."}}}})
```

Middleware descriptors are implemented as a map in var metadata under a
`:nrepl.middleware/descriptor` key.  Each descriptor can contain
any of three entries:

* `:requires`, a set containing strings or vars identifying other middleware
  that must be applied at a higher level than the middleware being described.
Var references indicate an implementation detail dependency; string values
indicate a dependency on _any_ middleware that handles the specified `:op`.
* `:expects`, the same as `:requires`, except the referenced middleware must
  exist in the final stack at a lower level than the middleware being
described.
* `:handles`, a map that documents the operations implemented by the
  middleware.  Each entry in this map must have as its key the string value of
the handled `:op` and a value that contains any of four entries:
  * `:doc`, a human-readable docstring for the middleware
  * `:requires`, a map of slots that the handled operation must find in request
    messages with the indicated `:op`
  * `:optional`, a map of slots that the handled operation may utilize from the
    request messages with the indicated `:op`
  * `:returns`, a map of slots that may be found in messages sent in response
    to handling the indicated `:op`

The values in the `:handles` map is used to support the `"describe"` operation,
which provides "a machine- and human-readable directory and documentation for
the operations supported by an nREPL endpoint" (see
`nrepl.middleware/describe-markdown`, and the results of
`"describe"` and `describe-markdown`
[here](https://github.com/nrepl/nrepl/blob/master/doc/ops.md)).

The `:requires` and `:expects` entries control the order in which
middleware is applied to a base handler.  In the `add-stdin` example above,
that middleware will be applied after any middleware that handles the `"eval"`
operation, but before the `nrepl.middleware.session/session`
middleware.  In the case of `add-stdin`, this ensures that incoming messages
hit the session middleware (thus ensuring that the user's dynamic scope —
including `*in*` — has been added to the message) before the `add-stdin`'s
handler sees them, so that it may append the provided `stdin` content to the
buffer underlying `*in*`.  Additionally, `add-stdin` must be "above" any `eval`
middleware, as it takes responsibility for calling `clojure.main/skip-if-eol`
on `*in*` prior to each evaluation (in order to ensure functional parity with
Clojure's default stream-based REPL implementation).

The specific contents of a middleware's descriptor depends entirely on its
objectives: which operations it is to implement/define, how it is to modify
incoming request messages, and which higher- and lower-level middlewares are to
aid in accomplishing its aims.

nREPL uses the dependency information in descriptors in order to produce a
linearization of a set of middleware; this linearization is exposed by
`nrepl.middleware/linearize-middleware-stack`, which is
implicitly used by `nrepl.server/default-handler` to combine the
default stack of middleware with any additional provided middleware vars.  The
primary contribution of `default-handler` is to use
`nrepl.server/unknown-op` as the base handler; this ensures that
unhandled messages will always produce a response message with an `:unknown-op`
`:status`.  Any handlers otherwise created (e.g. via direct usage of
`linearize-middleware-stack` to obtain a ordered sequence of middleware vars)
should do the same, or use a similar alternative base handler.

#### Sessions

Sessions persist [dynamic vars](https://clojure.org/reference/vars)
(collected by `get-thread-bindings`) against a unique lookup. This is
allows you to have a different value for `*e` from different REPL
clients (e.g. two separate REPL-y instances). An existing session can
be cloned to create a new one, which then can be modified. This allows
for copying of existing preferences into new environments.

Sessions become even more useful when different nREPL extensions start
taking advantage of
them. [debug-repl](https://github.com/gfredericks/debug-repl/) uses
sessions to store information about the current breakpoint, allowing
debugging of two things
separately. [piggieback](https://github.com/nrepl/piggieback) uses
sessions to allow host a ClojureScript REPL alongside an existing
Clojure one.

An easy mistake is to confuse a `session` with an `id`. The difference
between a session and id, is that an `id` is for tracking a single
message, and sessions are for tracking remote state. They're
fundamental to allowing simultaneous activities in the same nREPL.
For instance - if you want to evaluate two expressions simultaneously
you'll have to do this in separate session, as all requests within the
same session are serialized.
