# The first N sentences of a UTF-8 text file, on one line, their own marks kept:
# awk splits by bytes and cut Devanagari's danda (।) and the letters round it into
# fragments, so the voice probe was handed nothing it could say.
#   perl tools/bench/first_sentences.pl FILE [N]
use strict;
use warnings;
use utf8;
binmode STDOUT, ':encoding(UTF-8)';
my ($file, $n) = @ARGV;
$n ||= 3;
open my $fh, '<:encoding(UTF-8)', $file or die "$file: $!";
local $/;
my $text = <$fh>;
close $fh;
$text =~ s/\s+/ /g;
$text =~ s/^ | $//g;
my @sentences = split /(?<=[.?!\x{0964}])\s+/, $text;
print join(' ', @sentences[0 .. ($n > @sentences ? $#sentences : $n - 1)]);
