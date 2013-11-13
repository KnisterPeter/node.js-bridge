var util = require('util');

var writeResponse = function(response) {
	process.stdout.write(response + '\n');
}

var stdout = [];
var stderr = [];
console.log = function() {
  var args = Array.prototype.slice.call(arguments);
  args.forEach(function(arg) {
    stdout.push(util.inspect(arg));
  });
}
console.error = function() {
  var args = Array.prototype.slice.call(arguments);
  args.forEach(function(arg) {
    stderr.push(util.inspect(arg));
  });
}

process.stdin.resume();
process.stdin.setEncoding('utf8');

var lastChunk = null;
process.stdin.on('data', function(chunk) {
  stdout = [];
  stderr = [];
  try {
    console.log('chunk ends with newline: ' + (chunk.substring(chunk.length - 1) == '\n'));
    console.log('REQUESTED: ' + chunk);
    console.log('LAST     : ' + lastChunk);
    lastChunk = chunk;
    var command = JSON.parse(chunk);
    process.chdir(command.cwd);
    require('index')(command, function(output) {
        var result = {
            'stdout': stdout, 
            'stderr': stderr
          };
        if (!!output) result.result = output;
        writeResponse(JSON.stringify(result));
      });
  } catch (e) {
    writeResponse(JSON.stringify(
      {
        'error': util.inspect(e), 
        'stdout': stdout, 
        'stderr': stderr
      }));
  }
});

writeResponse('ipc-ready');
