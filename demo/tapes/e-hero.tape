Output out/e-hero.gif
Set Shell zsh
Set FontSize 17
Set Width 1300
Set Height 520
Set Padding 20
Set Theme "Dracula"
Set TypingSpeed 40ms

Hide
Type "export PATH=$PWD/bin:$PATH; clear" Enter
Show

Type "kubectl logs api-7d9f-x4k2 | loglens --stats --quiet" Sleep 500ms Enter
Hide
Type "clear; loglens --color --stats --quiet incident.log" Enter
Show
Sleep 5s
