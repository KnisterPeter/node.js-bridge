var util = require('util');
var net = require('net');

var writeResponse = function(socket, data) {
  socket.write(data + '\n');
}

var conout = [];
console.log = function() {
  var args = Array.prototype.slice.call(arguments);
  args.forEach(function(arg) {
    conout.push({level: 'INFO', message: util.inspect(arg)});
  });
}
console.error = function() {
  var args = Array.prototype.slice.call(arguments);
  args.forEach(function(arg) {
    conout.push({level: 'ERROR', message: util.inspect(arg)});
  });
}

var server = net.createServer(function(socket) {
  socket.on('data', function(chunk) {
    var chunk = chunk.toString().trim();
    if (chunk === 'FORCE-BREAK') {
      writeResponse(socket, JSON.stringify({'output': conout}));
      process.exit(1);
    } else {
      conout = [];
      try {
        var command = JSON.parse(chunk);
        var cmd = require('./index');
        process.chdir(command.cwd);
        cmd(command, function(output) {
          var result = {
            'output': conout 
          };
          if (!!output) result.result = output;
          writeResponse(socket, JSON.stringify(result));
        });
      } catch (e) {
        writeResponse(socket, 
          JSON.stringify({'error': util.inspect(e), 'output': conout}));
        process.exit(1);
      }
    }
  });
});
server.listen(0, '127.0.0.1', function() {
  process.stdout.write(server.address().port + '\n');
});
