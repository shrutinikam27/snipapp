const apiKey = "AIzaSyCQ8DIGe0jETi9fa94Sa1q7BDNhPItqJ2Q";
const url = `https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}`;

fetch(url).then(res => res.json()).then(data => {
  console.log(data.models.map(m => m.name).filter(n => n.includes('flash') || n.includes('pro')).join('\n'));
}).catch(console.error);
