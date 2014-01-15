var util = require('util');
var net = require('net');

function prepareOutput(type, args) {
  args.forEach(function(arg) {
    var str = util.inspect(arg);
    str.split('\n').forEach(function(line) {
      log('//' + type + ': ' + line);
    });
  });
}
var log = console.log;
console.log = function() {
  prepareOutput('OUT', Array.prototype.slice.call(arguments));
}
var err = console.error;
console.error = function() {
  prepareOutput('ERR', Array.prototype.slice.call(arguments));
}

try {
  var command = JSON.parse('' + process.argv[2]);
  var cmd = require('./index');
  process.chdir(command.cwd);
  cmd(command, function(output) {
    var result = {};
    if (!!output) result.result = output;
    log(JSON.stringify(result));
  });
} catch (e) {
  log(JSON.stringify({'error': util.inspect(e)}));
}
