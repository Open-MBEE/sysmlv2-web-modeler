// Lexical coloring only. The SysML service remains the authority for validation.
const keywords = new Set(`constant crosses decide do hastype istype locale meta rep specializes stakeholder standard about abstract accept action actor after alias all allocate allocation analysis and as assert assign assume at attribute bind binding by calc case comment concern connect connection constraint datatype def default defined dependency derived doc else end entry enum enumeration event exhibit exit expose false filter first flow for fork frame from if implies import in include individual inout interface is item join language library loop member merge message metadata nonunique not null objective occurrence of or ordered out package parallel part perform port private protected public redefines ref references render rendering require requirement return satisfy send snapshot specialization specialize state subject subsets succession terminate then timeslice to transition true until use variant variation verification verify via view viewpoint when while xor`.split(/\s+/));
const builtins = new Set(['Boolean','Integer','Natural','Positive','Rational','Real','String','Number','Anything']);
export function tokenizeSysML(text) {
  const pattern=/\/\/[^\r\n]*|\/\*[\s\S]*?(?:\*\/|$)|"(?:\\[\s\S]|[^"\\])*(?:"|$)|'(?:\\[\s\S]|[^'\\])*(?:'|$)|\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|\b[A-Za-z_][A-Za-z_0-9]*\b|[{}()[\]]|::|:>|:=|<=|>=|!=|==|[;:,.=+*/~<>@!?|&%-]/g;
  const tokens=[];let cursor=0,previous='';
  for(const match of text.matchAll(pattern)) {
    if(match.index>cursor)tokens.push({text:text.slice(cursor,match.index),start:cursor,kind:''});
    const value=match[0];let kind='';
    if(value.startsWith('//')||value.startsWith('/*'))kind='comment';
    else if(value.startsWith('"')||value.startsWith("'"))kind='string';
    else if(/^\d/.test(value))kind='number';
    else if(['true','false','null'].includes(value))kind='literal';
    else if(keywords.has(value))kind='keyword';
    else if(builtins.has(value))kind='type';
    else if(previous==='def')kind='declaration';
    else if(/^[{}()[\]]$/.test(value))kind='bracket';
    else if(!/^[A-Za-z_]/.test(value))kind='operator';
    tokens.push({text:value,start:match.index,kind});cursor=match.index+value.length;
    if(kind!=='comment')previous=value;
  }
  if(cursor<text.length)tokens.push({text:text.slice(cursor),start:cursor,kind:''});
  return tokens;
}
export function matchingBrackets(tokens,caret) {
  const brackets=tokens.filter(t=>t.kind==='bracket'),stack=[],pairs=new Map(),open='([{',close=')]}';
  for(const token of brackets){if(open.includes(token.text))stack.push(token);else {const prior=stack.pop();if(prior&&open.indexOf(prior.text)===close.indexOf(token.text)){pairs.set(prior.start,token.start);pairs.set(token.start,prior.start);}else stack.length=0;}}
  const selected=brackets.find(t=>t.start===caret)||brackets.find(t=>t.start===caret-1);
  return selected&&pairs.has(selected.start)?new Set([selected.start,pairs.get(selected.start)]):new Set();
}
const escape=value=>value.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function highlightTokens(tokens,matches=new Set()) {
  return tokens.map(t=>{const classes=[t.kind?'syntax-'+t.kind:'',matches.has(t.start)?'syntax-match':''].filter(Boolean).join(' ');return classes?`<span class="${classes}">${escape(t.text)}</span>`:escape(t.text);}).join('');
}
export function mountCodeEditor(source) {
  const $=id=>document.getElementById(id),shell=$('code-editor'),paint=$('code-highlight'),gutter=$('code-lines'),current=$('code-current'),position=$('code-position'),toggle=$('code-colors');
  let last=null,tokens=[],frame=0,composing=false;
  const lineHeight=()=>parseFloat(getComputedStyle(source).lineHeight)||24;
  function syncScroll(){paint.scrollTop=source.scrollTop;paint.scrollLeft=source.scrollLeft;gutter.style.transform=`translateY(${-source.scrollTop}px)`;const line=source.value.slice(0,source.selectionStart).split('\n').length;current.style.top=`${20+(line-1)*lineHeight()-source.scrollTop}px`;}
  function render(){frame=0;if(composing)return;const text=source.value;
    if(text!==last){last=text;tokens=tokenizeSysML(text);gutter.textContent=Array.from({length:text.split('\n').length},(_,i)=>i+1).join('\n');}
    const selected=text.slice(0,source.selectionStart),line=selected.split('\n').length,column=source.selectionStart-(selected.lastIndexOf('\n')+1)+1;
    position.textContent=`Line ${line}, column ${column} · ${text.split('\n').length} lines${source.readOnly?' · Read only':''}`;
    paint.innerHTML=highlightTokens(tokens,source.selectionStart===source.selectionEnd?matchingBrackets(tokens,source.selectionStart):new Set())+(text.endsWith('\n')||!text?' ': '');
    paint.style.width=source.clientWidth+'px';paint.style.height=source.clientHeight+'px';
    shell.classList.toggle('code-plain',!toggle.checked);current.hidden=source.disabled||!text;syncScroll();
  }
  function refresh(){if(!frame)frame=requestAnimationFrame(render);}
  source.addEventListener('input',refresh);source.addEventListener('scroll',syncScroll);
  for(const event of ['click','keyup','select','focus'])source.addEventListener(event,refresh);
  source.addEventListener('compositionstart',()=>{composing=true;shell.classList.add('code-composing');});
  source.addEventListener('compositionend',()=>{composing=false;shell.classList.remove('code-composing');refresh();});
  document.addEventListener('selectionchange',()=>{if(document.activeElement===source)refresh();});
  toggle.addEventListener('change',refresh);new ResizeObserver(refresh).observe(source);
  refresh();
  function reveal(offset,length=1){offset=Math.max(0,Math.min(source.value.length,offset));source.focus();source.setSelectionRange(offset,Math.min(source.value.length,offset+Math.max(0,length)));const line=source.value.slice(0,offset).split('\n').length;source.scrollTop=Math.max(0,(line-3)*lineHeight());source.scrollLeft=0;refresh();}
  return {refresh,reveal};
}
