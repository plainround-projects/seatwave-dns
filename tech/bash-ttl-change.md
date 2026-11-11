# Bash and `scripts/ttl-change.sh`

## What it is

A **terminal** is a window where you type commands. A **shell** is the program inside it that reads what you type and starts other programs for you. **Bash** is one very common shell, and also a small programming language: you can save commands in a file, called a **script**, and run the file. The script in this repository, `scripts/ttl-change.sh`, is written in Bash. The `.sh` ending is only a habit; the first line decides which program runs the file.

A **command** is a program name followed by **arguments**, the words that tell it what to do: `dig @127.0.0.1 -p 5355 seatwave.example A`. A **variable** is a named box for text: `port="5355"` stores the text, `$port` reads it. Every command ends with an **exit code**, a number from 0 to 255. By agreement 0 means "it worked" and any other number means "it failed". The shell remembers the last one in `$?`.

## Why this repository uses it

The TTL-change experiment has two halves. The Java program `ttlLive` moves the lab server's answer from one address to another. The script plays a client that never caches: once per second it asks the server with `dig` and writes down what it gets. A shell script is the right size for that: it only starts `dig` 30 times and records lines. The script is also a lesson in safe tooling. Its default is a **dry run** that sends nothing, a real run needs `--run`, it asks for a confirmation, it only talks to `127.0.0.1` on a high port, and it writes an audit line.

## Where it appears

| Path | What it does |
| --- | --- |
| `scripts/ttl-change.sh` | The only script. 85 lines, mode `-rwxr-xr-x` (executable). |
| `README.md` | Shows a real run: `scripts/ttl-change.sh 5355 seatwave.example 30 build/ttl/dig.txt --run --yes`. |
| `config/ttl-live.properties` | Sets the port 5355 of the live server that the script asks. |
| `.gitignore` | Keeps `build/ttl/dig.txt` and `build/ttl/audit.log` out of Git (`git.md`). |

## How it works underneath

1. You type `scripts/ttl-change.sh 5355 seatwave.example 30 build/ttl/dig.txt`. The shell sees that the file is executable and reads its first line, `#!/usr/bin/env bash`. A line that starts with `#!` (the **shebang**) names the program that must run the file. `env bash` means "find `bash` on the PATH", the list of folders where programs live.
2. Bash starts as a new **process** (a running program) and reads the script one command at a time. The words you typed after the script name arrive as `$1`, `$2`, `$3`, `$4` and the following numbers. `$#` is how many there are, `$0` is the script's own name and `"$@"` is all of them.
3. The script checks every input first. Any check that fails prints a message to **standard error**, the stream for messages about problems (`>&2` sends a message there), and stops with a code.
4. In dry-run mode it prints the plan and the `dig` command and stops with code 0. Nothing was sent.
5. In run mode, it loops: start `dig`, keep only the record lines, add one line to the output file, wait one second.
6. After the loop it counts the lines, writes an audit line and exits 0, or 1 when the count is wrong.

`dig` itself only asks a question and prints the answer (`dig.md`). It never changes the server. The queries travel as small UDP packets on the loopback address, which never leaves the computer.

## Walkthrough of this repository's use

### The header (lines 1 to 14)

```bash
#!/usr/bin/env bash
# Records, once per second, what dig gets for one name from the lab DNS server, so the TTL-change
# experiment shows when a client that never caches sees the new answer (Module 6, Section 8).
# Run it in a second terminal while ./gradlew -q ttlLive --args='config/ttl-live.properties' runs.
#
# Usage: scripts/ttl-change.sh <port> <name> <seconds> <output file> [--run] [--yes]
#   default   a dry run: print the plan and the dig command, send nothing
#   --run     send the queries; asks for confirmation on the terminal first
#   --yes     skip the question (for a reader who has already read the plan)
#
# Guardrails: the server is always 127.0.0.1 and the port must be 1024 to 65535, so no query ever
# leaves this machine and no privileged port is touched; the name must lie under seatwave.example;
# at most 300 queries, one per second; the output file must not exist yet; an audit line is
# appended next to the output; any failure exits non-zero. dig never changes anything: it only asks.
```

Line 1 is the shebang explained above. Every line that starts with `#` is a **comment**: Bash ignores it, and people read it. The comments are the script's manual:
- The usage line shows the four required arguments (`<port> <name> <seconds> <output file>`) and the two optional words in square brackets, `[--run]` and `[--yes]`.
- Without options the script is a dry run: it prints the plan and the `dig` command and sends nothing.
- `--run` sends the queries and asks for confirmation on the terminal first. `--yes` skips the question.
- The guardrails: the server is always `127.0.0.1`, the port must be 1024 to 65535, the name must lie under `seatwave.example`, at most 300 queries, one per second, the output file must not exist yet, an audit line is appended next to the output, and any failure exits with a non-zero code.

A **port** is a number that tells the operating system which program on this computer should receive a packet. Ports below 1024 are **privileged** on Unix: only the administrator may listen on them, and the real DNS port 53 is one of them. The lab servers use ports above 1024 so no administrator rights are needed.

### Strict mode (line 15)

```bash
set -euo pipefail
```

One line, three switches:
- `set -e` makes the script stop at the first command that fails (exits non-zero), instead of carrying on with wrong data. A failing command that is part of a test (after `if`, `||`, `&&`) does not count, because there the failure is the point.
- `set -u` makes it an error to read a variable that was never set. A typing mistake such as `$prot` then stops the script instead of becoming empty text.
- `set -o pipefail` changes how a **pipe** is judged. A pipe `a | b | c` sends the output of `a` into `b` and on into `c`. Normally the pipe's exit code is that of the last command only. With `pipefail` it fails if any command in it failed.

### Constants (lines 17 to 21)

```bash
readonly MAX_SECONDS=300
readonly LOWEST_PORT=1024
readonly HIGHEST_PORT=65535
readonly NAME_PATTERN='^([a-z0-9-]+\.)*seatwave\.example\.?$'
readonly DIG_TIMEOUT_SECONDS=1
```

`readonly NAME=value` creates a variable and forbids changing it later, so these five settings behave as constants. Capital letters are only a custom for constants. No spaces are allowed around `=`: `a = 1` would try to run a program named `a`.
- `MAX_SECONDS=300` is the longest run: 300 queries, one per second, five minutes.
- `LOWEST_PORT=1024` and `HIGHEST_PORT=65535` bound the port. 65535 is the largest port number, because a port is a 16-bit number.
- `NAME_PATTERN='^([a-z0-9-]+\.)*seatwave\.example\.?$'` is a **regular expression**, a pattern that describes text. `^` is the start and `$` the end of the text. `[a-z0-9-]+` is one or more lowercase letters, digits or hyphens. `\.` is a real dot (a bare `.` would mean any character). `( ... )*` means the group may repeat zero or more times, so `www.` and `a.b.` are both fine and nothing at all is fine too. `seatwave\.example` is the zone itself. `\.?` allows one optional final dot. So `seatwave.example`, `www.seatwave.example.` and `a.b.seatwave.example` pass, and `example.com` and `evilseatwave.example` (no dot before `seatwave`) do not. Single quotes keep the shell from touching the characters; inside them everything is literal.
- `DIG_TIMEOUT_SECONDS=1` is how long `dig` waits for an answer.

### The usage function (lines 23 to 26)

```bash
usage() {
  echo "usage: $0 <port> <name> <seconds> <output file> [--run] [--yes]" >&2
  exit 2
}
```

A **function** is a named group of commands that you run by writing its name. `usage() { ... }` defines one. Inside, `echo "usage: $0 ..." >&2` prints the text on standard error; `$0` is the name the script was started with. Double quotes let `$0` be replaced by its value but keep the text as one word. The text `<port>` inside quotes is plain text; outside quotes `<` would mean "read input from a file". `exit 2` ends the whole script with code 2, the code this script uses for "you called me wrongly". Exit code 1 is used for "something else went wrong".

### Reading the arguments (lines 28 to 35)

```bash
[[ $# -ge 4 ]] || usage
port="$1"
name="$2"
seconds="$3"
out_file="$4"
shift 4
mode="dry-run"
confirmed="no"
```

- `[[ $# -ge 4 ]] || usage`: `[[ ... ]]` is Bash's test. `-ge` means "greater than or equal" for numbers, so the test asks whether at least four arguments came. `A || B` runs `B` only if `A` failed, so with fewer than four arguments `usage` runs and the script exits with 2. Under `set -e` this form is allowed to "fail" safely.
- `port="$1"` copies the first argument into a variable. The double quotes keep text with spaces together. The next three lines do the same for the name, the seconds and the output file.
- `shift 4` removes the first four arguments, so `$1` is now the first of whatever is left, the options.
- `mode="dry-run"` and `confirmed="no"` are the defaults. Safety is the default, and a reader must opt in to sending.

### The option loop (lines 36 to 42)

```bash
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
done
```

`for option in "$@"; do ... done` repeats the block once for each remaining argument, putting the current one in `option`. `"$@"` expands to each argument as its own word, kept intact even if it contains spaces.

`case "$option" in ... esac` compares the value against patterns, one per branch. A branch is `pattern) commands ;;`, and `;;` ends it. `--run) mode="run" ;;` sets the mode. `--yes) confirmed="yes" ;;` records the confirmation. `*) usage ;;` is the catch-all: `*` matches anything, so any other word (`--go`, a typo, an extra file name) prints the usage and stops with 2. A typo can therefore never be silently ignored.

### Checks before doing anything (lines 44 to 51)

```bash
# Check before: the tool, the inputs and the output.
command -v dig >/dev/null || { echo "dig is not installed" >&2; exit 1; }
[[ "$port" =~ ^[1-9][0-9]*$ ]] && (( port >= LOWEST_PORT && port <= HIGHEST_PORT )) \
  || { echo "the port must be $LOWEST_PORT to $HIGHEST_PORT" >&2; exit 2; }
[[ "$name" =~ $NAME_PATTERN ]] || { echo "the name must lie under seatwave.example: $name" >&2; exit 2; }
[[ "$seconds" =~ ^[1-9][0-9]*$ ]] && (( seconds >= 1 && seconds <= MAX_SECONDS )) \
  || { echo "seconds must be 1 to $MAX_SECONDS" >&2; exit 2; }
[[ ! -e "$out_file" ]] || { echo "refusing to overwrite $out_file" >&2; exit 2; }
```

- `command -v dig >/dev/null || { echo "dig is not installed" >&2; exit 1; }`: `command -v dig` prints where the program `dig` is, or fails if it is not installed. `>/dev/null` throws the printed path away (`/dev/null` is a file that discards everything written to it). If the program is missing, the group in braces `{ ...; }` runs: print a message to standard error and exit with code 1. Inside the braces each command ends with `;`.
- The port check. `[[ "$port" =~ ^[1-9][0-9]*$ ]]` asks whether the text is a whole number that does not start with a zero (`=~` means "matches this regular expression"). The first digit must be 1 to 9, because inside `(( ))` Bash reads a number with a leading zero as octal: `05355` would count as 2797, and `08080` would be an error. `&& (( port >= LOWEST_PORT && port <= HIGHEST_PORT ))` then asks, only if the first part held, whether the number lies in range. Double round brackets `(( ... ))` do arithmetic and comparison on numbers, and inside them a variable name needs no `$`. The backslash `\` at the end of line 46 means "the command continues on the next line". `|| { ... exit 2; }` on line 47 runs if either part failed. So `abc`, `80` and `70000` are all refused with the message `the port must be 1024 to 65535` and code 2. The message uses `$LOWEST_PORT` and `$HIGHEST_PORT`, so the numbers are never repeated by hand.
- The name check. `[[ "$name" =~ $NAME_PATTERN ]]` tests the name against the pattern from line 20. The pattern on the right is deliberately not in quotes; in quotes Bash would compare the characters literally instead of as a regular expression. A failure prints `the name must lie under seatwave.example: ...` and exits 2.
- The seconds check, built like the port check, accepts only digits from 1 to `MAX_SECONDS`, and fails with `seconds must be 1 to 300`.
- `[[ ! -e "$out_file" ]]` asks whether the output file does not exist (`-e` means "exists", `!` negates). If it does exist the script says `refusing to overwrite ...` and exits 2. An earlier run's result is never silently destroyed.

### The plan and the dry run (lines 53 to 60)

```bash
dig_command=(dig @127.0.0.1 -p "$port" "$name" A +norecurse +noall +answer +time="$DIG_TIMEOUT_SECONDS" +tries=1)
echo "plan: $seconds queries, one per second, to 127.0.0.1:$port for $name A"
if [[ "$mode" == "dry-run" ]]; then
  echo "dry run: nothing sent. Each query would be:"
  echo "  ${dig_command[*]}"
  echo "add --run to send them"
  exit 0
fi
```

- `dig_command=( ... )` builds an **array**, a numbered list of words. Each word is one item: `dig`, `@127.0.0.1`, `-p`, the port, the name, `A`, and the options. `"$port"` and `"$name"` are quoted so each stays one item. The server is the literal `127.0.0.1`, written into the script, so no input can send a query anywhere else.
- The `dig` options: `@127.0.0.1` picks the server, `-p` the port, `A` the record type to ask for. `+norecurse` says "do not ask me to look further, answer from your own data" (the server here is not a recursive resolver). `+noall +answer` hides every part of the output except the answer section. `+time=1` waits at most 1 second for a reply and `+tries=1` tries once, so a dead server costs one second, not the default of several. `dig.md` explains each.
- `echo "plan: $seconds queries, ..."` always prints the plan, in both modes.
- `if [[ "$mode" == "dry-run" ]]; then ... fi` runs its block only in dry-run mode. `==` inside `[[ ]]` compares text. The block prints that nothing was sent, prints the command (`${dig_command[*]}` joins all array items with spaces for display), tells the reader to add `--run`, and `exit 0` ends the script successfully.

### The confirmation (lines 62 to 65)

```bash
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Send $seconds queries to 127.0.0.1:$port? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing sent" >&2; exit 1; }
fi
```

If `--yes` was not given, the script asks. `read -r -p "Send ... [y/N] " answer < /dev/tty` shows the prompt and stores the typed line in `answer`. `-p` gives the prompt text, and `-r` stops Bash from treating a backslash as an escape. `< /dev/tty` reads from the keyboard terminal itself and not from the script's input, so the question works even when the script's input comes from a pipe. In the prompt `[y/N]` the capital N shows the default: no. Then `[[ "$answer" == "y" ]] || { ...; exit 1; }` accepts only the lowercase `y`. Anything else, including pressing Enter, prints `not confirmed; nothing sent` and exits with code 1. If there is no terminal at all, `read` itself fails and `set -e` stops the script, which is again the safe outcome.

### Preparing the output (lines 67 to 69)

```bash
mkdir -p "$(dirname "$out_file")"
audit_file="$(dirname "$out_file")/audit.log"
started=$(date +%s)
```

- `mkdir -p "$(dirname "$out_file")"` makes the folder for the output file. `$( ... )` is **command substitution**: it runs the command inside and puts its output in place. `dirname` prints the folder part of a path, so `build/ttl/dig.txt` gives `build/ttl`. `-p` creates missing parent folders and does not complain if the folder exists.
- `audit_file="$(dirname "$out_file")/audit.log"` puts the audit log next to the output file.
- `started=$(date +%s)` stores the current time in seconds since 1 January 1970 (**epoch seconds**). Later lines subtract it to get "seconds since the start".

### The query loop (lines 70 to 76)

```bash
for (( query = 1; query <= seconds; query++ )); do
  # One line per query: seconds since the start, then dig's answer (name, TTL, class, type, address).
  # dig 9.10 prints its comment lines even with +noall, so only the record lines are kept.
  answer=$("${dig_command[@]}" | grep -v '^;' | grep -v '^$' | tr -s '\t ' ' ' || true)
  printf '%3d %s\n' "$(( $(date +%s) - started ))" "${answer:-no answer}" >> "$out_file"
  sleep 1
done
```

- `for (( query = 1; query <= seconds; query++ )); do` is a counting loop in C style: start at 1, continue while `query <= seconds`, add 1 after each round. The counter itself is not used inside; it only counts rounds.
- The two comment lines say what is written and why the next line filters: dig 9.10 prints comment lines (those that start with `;`) even with `+noall`, so only the record lines are kept.
- `answer=$("${dig_command[@]}" | grep -v '^;' | grep -v '^$' | tr -s '\t ' ' ' || true)` is a pipe of four commands inside `$( ... )`. `"${dig_command[@]}"` runs the array as a command, each item one word. `grep -v '^;'` drops every line that starts with `;`. `grep -v '^$'` drops empty lines. `tr -s '\t ' ' '` replaces each run of tabs or spaces with a single space, so the record is on one tidy line. `|| true` is essential: with `pipefail`, a failed `dig` (no answer) or a `grep` that finds nothing would otherwise stop the whole script. The `|| true` turns "no answer" into an empty `answer` and the loop goes on.
- `printf '%3d %s\n' "$(( $(date +%s) - started ))" "${answer:-no answer}" >> "$out_file"` writes one line. `printf` prints with a format: `%3d` is a whole number in a field three characters wide, `%s` is text, `\n` ends the line. `$(( ... ))` is arithmetic that gives a value: now minus the start time. `${answer:-no answer}` means "the value of `answer`, or the text `no answer` if it is empty". `>>` **appends** to the file (a single `>` would replace the file's content each round).
- `sleep 1` waits one second. The queries are one second apart, which is also the pace at which a real client that never caches would ask.

### The check after (lines 78 to 85)

```bash
# Check after: one line per query, or the run failed somewhere.
written=$(wc -l < "$out_file" | tr -d ' ')
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) sent $seconds queries to 127.0.0.1:$port for $name; wrote $written lines to $out_file" >> "$audit_file"
if [[ "$written" != "$seconds" ]]; then
  echo "expected $seconds lines, but $out_file has $written" >&2
  exit 1
fi
echo "wrote $written lines to $out_file; audit line added to $audit_file"
```

- `written=$(wc -l < "$out_file" | tr -d ' ')` counts the lines of the output file. `wc -l` counts lines; on macOS it pads the number with spaces, and `tr -d ' '` deletes them.
- The `echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) sent ..." >> "$audit_file"` line adds one **audit line**: a time stamp in UTC (`-u`) with the format `year-month-dayThour:minute:secondZ`, then what was done. The audit file is appended to, never rewritten, so repeated runs leave a history.
- `if [[ "$written" != "$seconds" ]]; then ... exit 1; fi` is the check after the work: there must be exactly one line per query. If not, the script says what it expected, on standard error, and exits with 1.
- The last `echo` reports success. The script ends, and its exit code is 0, the code of the last command.

### Exit codes in one table

| Code | Meaning in this script |
| --- | --- |
| 0 | A dry run finished, or all lines were written. |
| 1 | `dig` is missing, the answer to the question was not `y`, or the number of lines written was wrong. |
| 2 | Wrong use: too few arguments, an unknown option, a bad port, name or seconds, or an output file that already exists. |

The README shows one real run with `--run --yes` (the live experiment). I did **not** run `--run` for these notes: I ran only the dry run and the refusal cases below. The confirmation prompt and the loop are therefore explained from the code and from the README's recorded output, not from a run of mine.

## Try it yourself

All commands ran in a scratch copy on 11 November 2026 (GNU bash 5.3.9 from Homebrew was first on the PATH; macOS's own `/bin/bash` is 3.2.57). None of them sends a query.

1. Check the syntax without running anything:

```
bash -n scripts/ttl-change.sh; echo "syntax exit=$?"
```

   Output: `syntax exit=0`.

2. The dry run, which is the default:

```
scripts/ttl-change.sh 5355 seatwave.example 30 build/ttl/dig.txt; echo "exit=$?"
```

   Output:

```
plan: 30 queries, one per second, to 127.0.0.1:5355 for seatwave.example A
dry run: nothing sent. Each query would be:
  dig @127.0.0.1 -p 5355 seatwave.example A +norecurse +noall +answer +time=1 +tries=1
add --run to send them
exit=0
```

   No `build/ttl` folder was created.

3. The refusals (each prints one line and exits with 2):

```
scripts/ttl-change.sh 80 seatwave.example 30 build/ttl/dig.txt
scripts/ttl-change.sh 5355 example.com 30 build/ttl/dig.txt
scripts/ttl-change.sh 5355 seatwave.example 301 build/ttl/dig.txt
scripts/ttl-change.sh 5355 seatwave.example 5 README.md
scripts/ttl-change.sh 5355 seatwave.example 5 build/x.txt --go
```

   Outputs: `the port must be 1024 to 65535`; `the name must lie under seatwave.example: example.com`; `seconds must be 1 to 300`; `refusing to overwrite README.md`; and the `usage: ...` line. The names `evilseatwave.example`, `SeatWave.Example` and `a b.seatwave.example` are refused with the same `the name must lie under seatwave.example` message. Ports `abc` and `70000` give the port message.

4. Hide `dig` from the script (the script runs under an empty PATH):

```
env PATH=/nonexistent /opt/homebrew/bin/bash scripts/ttl-change.sh 5355 seatwave.example 2 build/ttl/dig.txt; echo "exit=$?"
```

   Output: `dig is not installed` and `exit=1`. (Use the path of your own `bash`.)

5. Run it under the old Bash 3.2 that macOS ships:

```
/bin/bash scripts/ttl-change.sh 5355 seatwave.example 2 build/ttl/dig.txt
```

   Output: the same dry-run text, with `plan: 2 queries, ...` on the first line. The checks and the dry run work under Bash 3.2; I did not test the run mode under it.

## Mistakes people make

- **Spaces around `=`** in an assignment (`a = 1`). Bash runs a program called `a`. The script writes `port="$1"`.
- **Unquoted variables.** `$name` containing a space splits into two words. The script quotes every use.
- **Quoting the regular expression** on the right of `=~`. Then it is compared as plain text. The script leaves `$NAME_PATTERN` unquoted on purpose.
- **`$*` instead of `"$@"`.** `$*` merges the arguments into one string; `"$@"` keeps each one separate.
- **Forgetting that a pipe hides failures.** Without `pipefail` the exit code of `dig | grep ...` is that of `grep`. With it, the script has to add `|| true` where an empty answer is expected, as line 73 does.
- **Numbers with a leading zero inside `(( ))`.** Bash reads `05355` as an octal number (base 8), which is 2797. The script accepts such a port, because 2797 is in range, and passes the text `05355` to `dig`, which reads it as decimal. The port `08080` makes Bash print `value too great for base` and the script then refuses it with the port message. The two cases are harmless here, but a script that must be exact would strip the leading zeros first.
- **Running a script that is not executable.** The file needs the mode `x` (`chmod +x`). It has it: `-rwxr-xr-x`.
- **Using `>` where `>>` is meant.** `>` replaces the file's content at every round.

## Check yourself

1. *What do the three switches of `set -euo pipefail` do?* `-e` stops at the first failing command, `-u` makes an unset variable an error, `pipefail` makes a pipe fail if any command in it fails.
2. *What does the script do when you run it with only four arguments?* It prints the plan and the `dig` command and exits 0 without sending anything: that is the dry run.
3. *Why does the name check refuse `evilseatwave.example`?* The pattern needs either the exact name `seatwave.example` or a dot before it; `evilseatwave.example` has neither.
4. *Why does the answer line end with `|| true`?* A failed `dig` or a `grep` that finds nothing would stop the script under `set -e` and `pipefail`. `|| true` makes it an empty answer, which is recorded as `no answer`.
5. *What are the three exit codes and what does each mean?* 0 success or dry run; 1 a missing tool, a refused confirmation or a wrong line count; 2 wrong use.

## Further reading

- Bash reference manual, including `set`, `[[ ]]`, `case`, arrays and `read`: <https://www.gnu.org/software/bash/manual/bash.html>
- POSIX definitions of `grep`, `tr`, `wc`, `date`, `printf` and `dirname`: <https://pubs.opengroup.org/onlinepubs/9799919799/idx/utilities.html>
- The `dig` manual page: <https://bind9.readthedocs.io/en/stable/manpages.html#dig-dns-lookup-utility>
