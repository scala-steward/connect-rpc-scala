# Connect-RPC Scala JSON Bridge

This library provides a bridge between [Connect-RPC](https://connectrpc.com/docs/protocol) protocol and
[ScalaPB](https://scalapb.github.io) GRPC compiler for Scala.
It is inspired and takes ideas from [grpc-json-bridge](https://github.com/avast/grpc-json-bridge) library, which seems
not supported anymore + it's not following a Connect-RPC standard (while being very close to it).

At the moment, only unary (non-streaming) methods are supported.

Library focuses on Scala 3.x support from the beginning.

## Usage

TODO: Add usage instructions

## Future improvements

* Support GET-requests
* Support non-unary methods