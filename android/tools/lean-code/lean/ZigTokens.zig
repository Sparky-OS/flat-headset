// SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
// SPDX-License-Identifier: MIT
//! The token streams Zig's own tokenizer (std.zig.Tokenizer, the compiler's lexer) reads from each
//! file named on the command line, for lang_zig's consumer check: a header line `== N` per file, then
//! one line per token, `tag<TAB>newline<TAB>bytes`, ending with the eof token. `newline` is 1 when a line
//! break lies between the previous token's start and this one's, the relation Zig's parser reads
//! (Parse.tokensOnSameLine: the same_line_doc_comment warning and where an error points); `bytes` is
//! the token's text in hexadecimal. Positions are left out, so two texts that differ only in their
//! removed comments print the same stream. Normal comments are no tokens; doc comments are.

const std = @import("std");

const hex = "0123456789abcdef";

pub fn main(init: std.process.Init) !void {
    const arena = init.arena.allocator();
    const io = init.io;
    const args = try init.minimal.args.toSlice(arena);

    var buffer: [64 * 1024]u8 = undefined;
    var file_writer: std.Io.File.Writer = .init(.stdout(), io, &buffer);
    const out = &file_writer.interface;

    for (args[1..], 0..) |path, n| {
        const source = try std.Io.Dir.cwd().readFileAllocOptions(io, path, arena, .limited(16 << 20), .of(u8), 0);
        try out.print("== {d}\n", .{n});
        var tokenizer = std.zig.Tokenizer.init(source);
        var previous: ?usize = null;
        while (true) {
            const token = tokenizer.next();
            const newline = if (previous) |start|
                std.mem.findScalar(u8, source[start..token.loc.start], '\n') != null
            else
                false;
            try out.writeAll(@tagName(token.tag));
            try out.writeAll(if (newline) "\t1\t" else "\t0\t");
            for (source[token.loc.start..token.loc.end]) |byte| {
                try out.writeByte(hex[byte >> 4]);
                try out.writeByte(hex[byte & 15]);
            }
            try out.writeByte('\n');
            if (token.tag == .eof) break;
            previous = token.loc.start;
        }
    }
    try out.flush();
}
