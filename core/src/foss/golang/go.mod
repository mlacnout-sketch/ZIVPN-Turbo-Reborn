module foss

go 1.21

require (
	cfa v0.0.0
	github.com/dlclark/regexp2 v1.11.5
	github.com/metacubex/mihomo v1.19.3
	golang.org/x/sync v0.11.0
	gopkg.in/yaml.v3 v3.0.1
)

replace cfa => ../../main/golang

replace github.com/metacubex/mihomo => ./clash
