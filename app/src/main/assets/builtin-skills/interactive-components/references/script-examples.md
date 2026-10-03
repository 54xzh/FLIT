# Script examples

Use labels in the user's language. These are independent complete surfaces.

## Budget calculation

Both sliders update the result locally; submission requests assistant analysis once.

```a2ui
{"version":"v0.9.1","createSurface":{"surfaceId":"budget-tool","catalogId":"flit:interactive/v1","flitRuntime":{"version":1,"code":"({calculate({model}){const remaining=Number(model.income)-Number(model.expenses);return [{path:\"/remaining\",value:remaining},{path:\"/summary\",value:\"Remaining: \"+remaining.toFixed(2)}]}})","watch":[{"paths":["/income","/expenses"],"handler":"calculate"}]}}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"budget-tool","components":[{"id":"root","component":"Column","children":["income","expenses","result","send"]},{"id":"income","component":"Slider","label":"Income","min":0,"max":10000,"value":{"path":"/income"}},{"id":"expenses","component":"Slider","label":"Expenses","min":0,"max":10000,"value":{"path":"/expenses"}},{"id":"result","component":"Text","text":{"path":"/summary"}},{"id":"send-label","component":"Text","text":"Ask assistant to analyze"},{"id":"send","component":"Button","child":"send-label","action":{"event":{"name":"analyze_budget","context":{"Income":{"path":"/income"},"Expenses":{"path":"/expenses"},"Remaining":{"path":"/remaining"}}}}}]}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"budget-tool","path":"/","value":{"income":5000,"expenses":3000,"remaining":2000,"summary":"Remaining: 2000.00"}}}
```

## Local list filtering

Search stays entirely local; no submit button is needed.

```a2ui
{"version":"v0.9.1","createSurface":{"surfaceId":"filter-tool","catalogId":"flit:interactive/v1","flitRuntime":{"version":1,"code":"({filter({model}){const query=model.query.toLowerCase();return [{path:\"/filtered\",value:model.items.filter(item=>item.name.toLowerCase().includes(query))}]}})","watch":[{"paths":["/query","/items"],"handler":"filter"}]}}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"filter-tool","components":[{"id":"root","component":"Column","children":["query","list"]},{"id":"query","component":"TextField","label":"Search fruit","value":{"path":"/query"}},{"id":"list","component":"List","children":{"componentId":"item","path":"/filtered"}},{"id":"item","component":"Text","text":{"path":"name"}}]}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"filter-tool","path":"/","value":{"query":"","items":[{"name":"Apple"},{"name":"Banana"},{"name":"Pear"}],"filtered":[{"name":"Apple"},{"name":"Banana"},{"name":"Pear"}]}}}
```

## Quiz scoring

Check repeatedly locally; the explanation button submits once.

```a2ui
{"version":"v0.9.1","createSurface":{"surfaceId":"quiz-tool","catalogId":"flit:interactive/v1","flitRuntime":{"version":1,"code":"({score({model}){const correct=model.answer.length===1&&model.answer[0]===\"4\";return [{path:\"/score\",value:correct?1:0},{path:\"/feedback\",value:correct?\"Correct: 1 point\":\"Try again: 0 points\"}]}})","watch":[]}}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"quiz-tool","components":[{"id":"root","component":"Column","children":["question","answer","check","feedback","send"]},{"id":"question","component":"Text","text":"What is 2 + 2?"},{"id":"answer","component":"ChoicePicker","label":"Your answer","options":[{"label":"3","value":"3"},{"label":"4","value":"4"},{"label":"5","value":"5"}],"value":{"path":"/answer"},"variant":"mutuallyExclusive"},{"id":"check-label","component":"Text","text":"Check answer"},{"id":"check","component":"Button","child":"check-label","action":{"functionCall":{"call":"runScript","args":{"handler":"score","args":{}}}}},{"id":"feedback","component":"Text","text":{"path":"/feedback"}},{"id":"send-label","component":"Text","text":"Ask assistant to explain"},{"id":"send","component":"Button","child":"send-label","action":{"event":{"name":"explain_answer","context":{"Answer":{"path":"/answer"},"Score":{"path":"/score"}}}}}]}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"quiz-tool","path":"/","value":{"answer":[],"score":0,"feedback":"Choose an answer, then check it."}}}
```
