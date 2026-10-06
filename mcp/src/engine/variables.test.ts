import { describe, expect, it } from 'vitest';
import { withEmptyLists } from './variables';

describe('withEmptyLists', () => {
  const schema = {
    type: 'object',
    properties: { additionalOwners: { type: 'array' }, age: { type: 'integer' } },
  };

  it('sends a list field the caller left out as an empty list', () => {
    expect(withEmptyLists({ age: 40 }, schema)).toEqual({ age: 40, additionalOwners: [] });
  });

  it('keeps a list the caller sent and adds nothing else', () => {
    const owners = [{ name: 'Lisa', email: 'lisa@example.test' }];
    expect(withEmptyLists({ additionalOwners: owners }, schema)).toEqual({
      additionalOwners: owners,
    });
  });
});
