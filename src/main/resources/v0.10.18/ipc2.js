var util = require('util');
var net = require('net');

var writeResponse = function(socket, data) {
  socket.write(data + '\n');
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

var server = net.createServer(function(socket) {
  socket.on('data', function(chunk) {
    stdout = [];
    stderr = [];
    var chunk = chunk.toString().trim();
    try {
      var command = JSON.parse(chunk);
      var cmd = require('./index');
      process.chdir(command.cwd);
      cmd(command, function(output) {
          var result = {
            'stdout': stdout, 
            'stderr': stderr
          };
          if (!!output) result.result = output;
          writeResponse(socket, JSON.stringify(result));
        });
    } catch (e) {
      process.stderr.write(JSON.stringify({
        'error': util.inspect(e),
        'stdout': stdout, 
        'stderr': stderr
      }) + '\n');
      process.exit(1);
    }
  });
});
server.listen(0, '127.0.0.1', function() {
  process.stdout.write(server.address().port + '\n');
});
