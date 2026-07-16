function typedInputValue(input, raw) {
  if (input.pipeline_type === 'int') {
    const text = String(raw)
    return /^-?\d+$/.test(text) ? Number(text) : raw
  }
  if (input.pipeline_type === 'bool') {
    if (raw === true || raw === 'true') return true
    if (raw === false || raw === 'false') return false
  }
  return raw
}

export function substituteInputPlaceholders(value, inputs, data) {
  if (Array.isArray(value)) {
    return value.map((item) => substituteInputPlaceholders(item, inputs, data))
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [
        key,
        substituteInputPlaceholders(item, inputs, data),
      ]),
    )
  }
  if (typeof value !== 'string') return value

  const whole = value.match(/^\{([^{}]+)}$/)
  if (whole) {
    const input = inputs.find((field) => field.name === whole[1])
    if (!input || data[whole[1]] === undefined) return value
    return typedInputValue(input, data[whole[1]])
  }

  return value.replace(/\{([^{}]+)}/g, (placeholder, key) => {
    return data[key] === undefined ? placeholder : String(data[key])
  })
}
