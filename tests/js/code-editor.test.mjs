import assert from 'node:assert/strict';
import {tokenizeSysML,matchingBrackets,highlightTokens} from '../../app/static/code-editor.js';
const text=`package Test {\n  /* part def Fake {\n     multiline comment } */\n  part def Satellite {\n    attribute mass : Real = 1.2e+3; // literal "ignored"\n    attribute title : String = "a \\"quoted\\" <tag>";\n    part 'quoted name';\n  }\n}\n`;
const tokens=tokenizeSysML(text);
assert.equal(tokens.map(t=>t.text).join(''),text,'tokenization changes source');
assert.equal(tokens.find(t=>t.text==='package').kind,'keyword');
assert.equal(tokens.find(t=>t.text==='Satellite').kind,'declaration');
assert.equal(tokens.find(t=>t.text==='Real').kind,'type');
assert.equal(tokens.find(t=>t.text==='1.2e+3').kind,'number');
assert.equal(tokens.find(t=>t.text.startsWith('/*')).kind,'comment');
assert.equal(tokens.find(t=>t.text==="'quoted name'").kind,'string');
const open=text.indexOf('{');assert.deepEqual([...matchingBrackets(tokens,open)].sort((a,b)=>a-b),[open,text.lastIndexOf('}')]);
assert.equal(matchingBrackets(tokenizeSysML('{[}]'),0).size,0,'mismatched brackets falsely paired');
assert.equal(matchingBrackets(tokenizeSysML('"{}" //[]'),1).size,0);
assert.equal(tokenizeSysML('/* unfinished\npart def X')[0].kind,'comment');
assert.equal(tokenizeSysML('"unfinished <x>')[0].kind,'string');
const markup=highlightTokens(tokenizeSysML('<script>alert("x")</script> &'));
assert.equal(markup.includes('<script>'),false);assert.ok(markup.includes('&lt;'));assert.ok(markup.includes('&amp;'));
assert.equal(tokenizeSysML('').length,0);
assert.equal(tokenizeSysML('a\t\r\nβ 😀').map(t=>t.text).join(''),'a\t\r\nβ 😀');
console.log('SysML coloring: lossless tokens, multiline comments, escaped strings, numbers, types, bracket pairing, unfinished text and escaping: PASS');

// Preserve coloring for keywords supported by the upstream editor.
for (const keyword of ['constant', 'crosses', 'decide', 'do', 'hastype', 'istype', 'locale', 'meta', 'rep', 'specializes', 'stakeholder', 'standard']) assert.equal(tokenizeSysML(keyword)[0].kind, "keyword");
