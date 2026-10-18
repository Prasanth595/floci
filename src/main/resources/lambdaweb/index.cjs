'use strict';
// The runtime entry Floci starts as /var/runtime/index.js, where AWS starts its own. It turns
// console output into the JSON log events AWS writes, with the request each one belongs to,
// filters the output by applicationLogLevel, then imports the app.
const { AsyncLocalStorage } = require('node:async_hooks');
const http = require('node:http');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const util = require('node:util');

// FATAL is above ERROR. Node has no console method for it, so it drops every console call.
const LEVELS = { TRACE: 0, DEBUG: 1, INFO: 2, WARN: 3, ERROR: 4, FATAL: 5 };
const minimum = LEVELS[process.env.AWS_LAMBDA_LOG_LEVEL] ?? LEVELS.INFO;
const region = process.env.AWS_REGION;
const requests = new AsyncLocalStorage();
const writeOut = process.stdout.write.bind(process.stdout);
const writeErr = process.stderr.write.bind(process.stderr);

const emit = http.Server.prototype.emit;
http.Server.prototype.emit = function (event, request) {
  if (event !== 'request') {
    return emit.apply(this, arguments);
  }
  const context = {
    requestId: request.headers['lambda-web-request-id'],
    endpointName: request.headers['lambda-web-endpoint-name'],
  };
  return requests.run(context, () => emit.apply(this, arguments));
};

function withErrors(key, value) {
  if (!(value instanceof Error)) {
    return value;
  }
  return { errorType: value.name, errorMessage: value.message, stackTrace: String(value.stack).split('\n'), ...value };
}

function line(level, message) {
  const context = requests.getStore();
  const event = {
    timestamp: new Date().toISOString(),
    level,
    message,
    requestId: context?.requestId,
    endpointName: context?.endpointName,
    region,
  };
  return JSON.stringify(event, withErrors) + '\n';
}

function render(level, args) {
  if (args.length !== 1 || typeof args[0] === 'string') {
    return line(level, args.length === 0 ? undefined : util.format(...args));
  }
  try {
    return line(level, args[0]);
  } catch (unserializable) {
    // A BigInt or a cycle is not JSON. AWS writes the text Node prints for it.
    return line(level, util.format(...args));
  }
}

function patch(name, level, write) {
  console[name] = function (...args) {
    if (LEVELS[level] >= minimum) {
      write(render(level, args));
    }
  };
}

patch('log', 'INFO', writeOut);
patch('info', 'INFO', writeOut);
patch('debug', 'DEBUG', writeOut);
patch('warn', 'WARN', writeErr);
patch('error', 'ERROR', writeErr);
console.trace = function (...args) {
  if (LEVELS.TRACE >= minimum) {
    const frames = String(new Error().stack).split('\n').slice(1).filter((frame) => !frame.includes(__filename));
    writeOut(line('TRACE', { traceMessage: util.format(...args), stackTrace: frames }));
  }
};

// Output that does not go through console counts as INFO.
if (LEVELS.INFO < minimum) {
  const drop = (chunk, encoding, callback) => {
    const done = typeof encoding === 'function' ? encoding : callback;
    if (typeof done === 'function') {
      process.nextTick(done);
    }
    return true;
  };
  process.stdout.write = drop;
  process.stderr.write = drop;
}

const entry = process.env.AWS_LAMBDA_NODEJS_ENTRYPOINT || 'index.js';
import(pathToFileURL(path.resolve('/var/task', entry)).href);
