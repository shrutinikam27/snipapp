import fetch from 'node-fetch';

const apiKey = "AIzaSyCY0-busT9hrg3sT69f6qd13s6_YtFqs-w";
const url = `https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}`;

fetch(url).then(res => res.json()).then(console.log).catch(console.error);
