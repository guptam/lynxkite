Vegas("Simple bar chart")
  .withData(table)
  .encodeX("product", Ordinal)
  .encodeY("cnt", Quantitative)
  .mark(Bar)