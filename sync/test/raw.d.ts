// vite ?raw imports, used by the contract tests to read config and source text
declare module "*?raw" {
  const content: string
  export default content
}
